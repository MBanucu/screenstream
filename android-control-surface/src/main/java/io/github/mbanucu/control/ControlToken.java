package io.github.mbanucu.control;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * The capability token that lets an ordinary app uid drive a control surface.
 *
 * <p>The token is {@value #BYTES} random bytes, carried as {@value #HEX}
 * lowercase hex characters, in a file only the app's owner can read. It is
 * minted only by a shell-only method, never as a side effect of an ordinary
 * request, so an app that was not given the token cannot make one appear.
 * Verification is constant-time: a wrong guess learns nothing beyond "no", and
 * neither a missing file nor a malformed guess reveals whether a token exists.
 *
 * <p>The storage is an interface because the device's directory is an Android
 * concern but the mint/verify rules are not: tests use an in-memory store and
 * run on the JVM, while {@link #atFile(File)} is the on-device store.
 */
public final class ControlToken {

    public static final String DEFAULT_FILE = "control.token";
    public static final int BYTES = 32;
    public static final int HEX = BYTES * 2;

    /** Where a token is kept. {@code read} returns null when there is none. */
    public interface Store {
        byte[] read() throws IOException;

        void write(byte[] raw) throws IOException;

        /** A path for the mint reply, or null when the store has no path. */
        String describe();
    }

    private final Store store;
    private final SecureRandom random;

    private ControlToken(Store store, SecureRandom random) {
        this.store = store;
        this.random = random;
    }

    public static ControlToken of(Store store) {
        return new ControlToken(store, new SecureRandom());
    }

    /** The same, with an injectable random source for a deterministic test. */
    public static ControlToken of(Store store, SecureRandom random) {
        return new ControlToken(store, random);
    }

    /** A token in {@code file}, created on first {@link #issue()}. */
    public static ControlToken atFile(File file) {
        return of(new FileStore(file));
    }

    public Store store() {
        return store;
    }

    /**
     * Returns the stored token as hex, minting it on first use.
     *
     * <p>A short or empty file is reported rather than replaced: replacing it
     * would silently invalidate the copy a shell already installed. Deleting the
     * file is the documented way to mint a new one.
     */
    public synchronized String issue() throws IOException {
        byte[] existing = store.read();
        if (existing != null) {
            if (existing.length != BYTES) {
                throw new IOException("unreadable control token at " + describe()
                        + " (" + existing.length + " bytes, expected " + BYTES
                        + "); delete it to mint a new one");
            }
            return toHex(existing);
        }
        byte[] raw = new byte[BYTES];
        random.nextBytes(raw);
        store.write(raw);
        return toHex(raw);
    }

    /** True if {@code presented} is the stored token. Never mints. */
    public boolean accept(String presented) {
        byte[] expected = null;
        try {
            expected = store.read();
        } catch (IOException ignored) {
            // An unreadable token is no token: refuse. The caller's reply says
            // nothing about whether a token exists.
        }
        return matches(expected, presented);
    }

    /** A path for the mint reply, or null when the store cannot name one. */
    public String describe() {
        return store.describe();
    }

    /** The comparison rule, separated from storage so it can be tested alone. */
    public static boolean matches(byte[] expected, String presented) {
        byte[] candidate = decodeHex(presented);
        byte[] safe = candidate;
        if (safe == null) {
            safe = new byte[expected == null ? 0 : expected.length];
        }
        byte[] wanted = expected == null ? new byte[0] : expected;
        boolean equal = MessageDigest.isEqual(wanted, safe);
        return expected != null && candidate != null && equal;
    }

    private static byte[] decodeHex(String text) {
        if (text == null || text.length() != HEX) {
            return null;
        }
        byte[] out = new byte[BYTES];
        for (int i = 0; i < BYTES; i++) {
            int hi = nibble(text.charAt(i * 2));
            int lo = nibble(text.charAt(i * 2 + 1));
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int nibble(char ch) {
        if (ch >= '0' && ch <= '9') {
            return ch - '0';
        }
        if (ch >= 'a' && ch <= 'f') {
            return ch - 'a' + 10;
        }
        return -1;
    }

    static String toHex(byte[] raw) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[raw.length * 2];
        for (int i = 0; i < raw.length; i++) {
            int value = raw[i] & 0xff;
            out[i * 2] = digits[value >>> 4];
            out[i * 2 + 1] = digits[value & 0x0f];
        }
        return new String(out);
    }

    /** The on-device store: one 0600 file, written through a temporary file. */
    public static final class FileStore implements Store {
        private final File file;

        public FileStore(File file) {
            this.file = file;
        }

        @Override
        public byte[] read() throws IOException {
            if (!file.isFile()) {
                return null;
            }
            InputStream in = new FileInputStream(file);
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[64];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buffer.write(chunk, 0, n);
                }
                return buffer.toByteArray();
            } finally {
                in.close();
            }
        }

        @Override
        public void write(byte[] raw) throws IOException {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("could not create " + dir);
            }
            if (dir != null) {
                restrict(dir, true);
            }
            File temporary = new File(file.getPath() + ".tmp");
            OutputStream out = new FileOutputStream(temporary);
            try {
                out.write(raw);
                out.flush();
            } finally {
                out.close();
            }
            restrict(temporary, false);
            if (!temporary.renameTo(file)) {
                // Some filesystems refuse to replace; keep the bytes and the
                // permission rather than leaving a temporary where a token
                // should be.
                throw new IOException("could not install " + file);
            }
            restrict(file, false);
        }

        @Override
        public String describe() {
            return file.getAbsolutePath();
        }

        public File file() {
            return file;
        }
    }

    private static void restrict(File file, boolean directory) {
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setExecutable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
        if (directory) {
            file.setExecutable(true, true);
        }
    }
}
