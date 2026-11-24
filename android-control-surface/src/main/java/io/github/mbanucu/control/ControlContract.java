package io.github.mbanucu.control;

/**
 * The frozen wire contract: the reply keys, the codes and the schema version.
 *
 * <p>The full document is {@code docs/control-surface.md}; these constants are
 * what the Java side uses, and {@code test/test_control.py} reads {@link
 * #SCHEMA_VERSION} from this file so the Python parser and the library cannot
 * drift without a test failing.
 *
 * <p>Schema 1 is:
 *
 * <ul>
 *   <li>the reply Bundle has exactly {@code code} (int) and {@code data} (String);
 *   <li>{@code code} is 0 for success, 400 when the call was understood but could
 *       not be done, and 403 when the caller is not allowed to call at all;
 *   <li>{@code data} is zero or more {@code key=value} lines, split on the first
 *       {@code =}, with newlines in a value replaced by spaces;
 *   <li>a provider that wants to advertise the schema adds a {@code schema=1}
 *       line. screenstream does not, because its replies predate the library and
 *       existing readers are byte-for-byte compatible.
 * </ul>
 */
public final class ControlContract {

    /** The version of the reply/extras grammar in this library. */
    public static final int SCHEMA_VERSION = 1;

    public static final String CODE = "code";
    public static final String DATA = "data";
    public static final String SCHEMA = "schema";

    /** Optional extra: a capability token for an ordinary app uid. */
    public static final String TOKEN = "token";

    /** The token reply's field name; the value is 64 lowercase hex characters. */
    public static final String TOKEN_VALUE = "token";

    /** Where a minted token was stored, when the store can name a path. */
    public static final String TOKEN_PATH = "path";

    public static final int OK = 0;
    public static final int BAD_REQUEST = 400;
    public static final int FORBIDDEN = 403;

    /** Reserved for an unexpected failure in the library itself; not part of 1. */
    public static final int INTERNAL = 500;

    private ControlContract() {
    }
}
