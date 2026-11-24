package io.github.mbanucu.control;

import java.util.Map;

/**
 * One control reply: the {@code code} and the {@code data} the provider returns.
 *
 * <p>This class is plain Java on purpose. The rules that keep a reply parseable -
 * one field per line, no newline in a value - are decided here, so they are
 * tested on the build machine's JVM and not only on a phone.
 *
 * <p>{@link #keyValue(int, Object...)} takes alternating keys and values and
 * joins them with {@code '\n'}, with no trailing newline. That is the shape of
 * an error or refusal line; a provider with a body of its own (screenstream's
 * status report) uses {@link #of(int, String)} and appends with {@link
 * #line(String, Object)}.
 */
public final class Reply {

    public final int code;
    public final String data;

    private Reply(int code, String data) {
        this.code = code;
        this.data = data == null ? "" : data;
    }

    /** A reply whose data is already in final form. */
    public static Reply of(int code, String data) {
        return new Reply(code, data);
    }

    public static Reply ok(String data) {
        return new Reply(ControlContract.OK, data);
    }

    public static Reply badRequest(String error) {
        return keyValue(ControlContract.BAD_REQUEST, "error", error);
    }

    public static Reply forbidden(String error) {
        return keyValue(ControlContract.FORBIDDEN, "error", error);
    }

    /**
     * One {@code key=value} line per pair, in order, joined by newlines.
     *
     * @param code 0, 400 or 403
     * @param pairs alternating key and value; a value is escaped with
     *              {@link #oneLine(Object)}
     */
    public static Reply keyValue(int code, Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("keyValue needs alternating keys and values");
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(pairs[i]).append('=').append(oneLine(pairs[i + 1]));
        }
        return new Reply(code, out.toString());
    }

    /** The same, from a map's iteration order. */
    public static Reply keyValue(int code, Map<String, ?> fields) {
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, ?> entry : fields.entrySet()) {
            if (!first) {
                out.append('\n');
            }
            first = false;
            out.append(entry.getKey()).append('=').append(oneLine(entry.getValue()));
        }
        return new Reply(code, out.toString());
    }

    /**
     * Appends one field to a body, keeping any existing trailing newline style.
     *
     * <p>A body that already ends in a newline gets {@code line\n}; one that does
     * not gets {@code \nline\n}. That is exactly how screenstream's reports have
     * always been built, so migrating them to this method does not change a byte.
     */
    public Reply line(String key, Object value) {
        String line = key + "=" + oneLine(value);
        String body = data.endsWith("\n") ? data + line + "\n" : data + "\n" + line + "\n";
        return new Reply(code, body);
    }

    /**
     * The escaping rule: a value never contains CR or LF.
     *
     * <p>There is deliberately no other escaping. A value may contain {@code =},
     * {@code :} or spaces, because a reader splits a line on its first {@code =},
     * and paths and error sentences are more readable without quoting.
     */
    public static String oneLine(Object value) {
        if (value == null) {
            return "";
        }
        String text = value instanceof String ? (String) value : String.valueOf(value);
        return text.replace('\n', ' ').replace('\r', ' ');
    }

    public boolean ok() {
        return code == ControlContract.OK;
    }

    @Override
    public String toString() {
        return "Reply{" + code + ", " + data + "}";
    }
}
