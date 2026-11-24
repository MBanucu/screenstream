package io.github.mbanucu.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;

import org.junit.Test;

public class ControlTokenTest {

    /** A store that lives in memory: the token rules, with no device and no file. */
    private static final class MemoryStore implements ControlToken.Store {
        byte[] bytes;

        @Override
        public byte[] read() {
            return bytes == null ? null : bytes.clone();
        }

        @Override
        public void write(byte[] raw) {
            bytes = raw.clone();
        }

        @Override
        public String describe() {
            return null;
        }
    }

    private static ControlToken deterministic(ControlToken.Store store) {
        SecureRandom random = new SecureRandom() {
            private int next;

            @Override
            public void nextBytes(byte[] bytes) {
                for (int i = 0; i < bytes.length; i++) {
                    bytes[i] = (byte) (next++ & 0xff);
                }
            }
        };
        return ControlToken.of(store, random);
    }

    @Test
    public void issueMintsSixtyFourLowercaseHexCharacters() throws IOException {
        ControlToken token = deterministic(new MemoryStore());
        String minted = token.issue();
        assertEquals(ControlToken.HEX, minted.length());
        assertTrue(minted, minted.matches("[0-9a-f]{64}"));
        assertEquals(minted, token.issue());
    }

    @Test
    public void acceptTakesTheMintedTokenAndNothingElse() throws IOException {
        ControlToken token = deterministic(new MemoryStore());
        String minted = token.issue();
        assertTrue(token.accept(minted));
        assertFalse(token.accept(null));
        assertFalse(token.accept(""));
        assertFalse(token.accept(minted.toUpperCase()));
        assertFalse(token.accept(minted.substring(1)));
        assertFalse(token.accept(minted + "0"));
        assertFalse(token.accept("not-a-token"));
        char first = minted.charAt(0);
        char changed = first == 'a' ? 'b' : 'a';
        assertFalse(token.accept(changed + minted.substring(1)));
    }

    @Test
    public void aMissingTokenIsNoTokenAndAcceptNeverMints() {
        MemoryStore store = new MemoryStore();
        ControlToken token = ControlToken.of(store);
        assertFalse(token.accept("00000000000000000000000000000000"
                + "00000000000000000000000000000000"));
        assertNull(store.bytes);
    }

    @Test
    public void aShortFileIsReportedRatherThanReplaced() {
        MemoryStore store = new MemoryStore();
        store.bytes = new byte[]{1, 2, 3};
        ControlToken token = ControlToken.of(store);
        try {
            token.issue();
            fail("a short token file was silently replaced");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("expected 32"));
        }
        assertEquals(3, store.bytes.length);
    }

    @Test
    public void matchesComparesTheBytesInConstantTimeShape() {
        byte[] expected = new byte[ControlToken.BYTES];
        expected[0] = 0;
        expected[1] = 1;
        expected[2] = 2;
        String hex = ControlToken.toHex(expected);
        assertTrue(ControlToken.matches(expected, hex));
        char last = hex.charAt(ControlToken.HEX - 1);
        String changed = hex.substring(0, ControlToken.HEX - 1)
                + (last == 'f' ? '0' : 'f');
        assertFalse(ControlToken.matches(expected, changed));
        assertFalse(ControlToken.matches(null, hex));
        assertFalse(ControlToken.matches(expected, hex.substring(1)));
        assertFalse(ControlToken.matches(expected, hex.substring(0, 63) + "g"));
    }

    @Test
    public void theFileStoreRoundTripsAndDescribesItsPath() throws IOException {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "control-token-" + System.nanoTime());
        File file = new File(dir, ControlToken.DEFAULT_FILE);
        try {
            ControlToken token = ControlToken.atFile(file);
            String minted = token.issue();
            assertEquals(file.getAbsolutePath(), token.describe());
            assertTrue(file.isFile());
            assertEquals(32, file.length());
            assertTrue(token.accept(minted));
            assertEquals(minted, ControlToken.atFile(file).issue());
        } finally {
            file.delete();
            dir.delete();
        }
    }
}
