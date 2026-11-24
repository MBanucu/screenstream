package io.github.mbanucu.control;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code content call} grammar, in one place.
 *
 * <p>Two shapes are frozen here, and the Python client implements the same two
 * and is tested against the same fixture file:
 *
 * <ul>
 *   <li>a call is {@code --uri URI --method METHOD [--extra name:type:value]...},
 *       optionally prefixed by {@code adb [-s SERIAL] shell content call};
 *   <li>the command prints {@code Result: Bundle[{code=N, data=...}]}, where
 *       {@code data} is the key=value body described in {@code Reply}.
 * </ul>
 *
 * <p>Extra types are the ones the platform's {@code content} command accepts:
 * {@code s} string, {@code i} int, {@code l} long, {@code f} float, {@code d}
 * double and {@code z} boolean. The value keeps any further colons, so
 * {@code --extra why:s:a:b} is the string {@code a:b}.
 */
public final class ContentCall {

    /** The schema version of this grammar; see {@link ControlContract}. */
    public static final int SCHEMA_VERSION = ControlContract.SCHEMA_VERSION;

    private static final Pattern RESULT =
            Pattern.compile("Result: Bundle\\[\\{code=(-?\\d+), data=(.*)\\}\\]\\s*$",
                    Pattern.DOTALL);

    private ContentCall() {
    }

    /** One {@code --extra name:type:value}. */
    public static final class Extra {
        public final String name;
        public final String type;
        public final String value;

        public Extra(String name, String type, String value) {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("an extra needs a name");
            }
            if (type == null || type.length() != 1 || "silfdz".indexOf(type.charAt(0)) < 0) {
                throw new IllegalArgumentException("unknown extra type '" + type
                        + "'; use s, i, l, f, d or z");
            }
            this.name = name;
            this.type = type;
            this.value = value == null ? "" : value;
        }

        public static Extra parse(String text) {
            if (text == null) {
                throw new IllegalArgumentException("an extra is name:type:value");
            }
            int first = text.indexOf(':');
            int second = first < 0 ? -1 : text.indexOf(':', first + 1);
            if (first <= 0 || second < 0) {
                throw new IllegalArgumentException("extra '" + text
                        + "' is not name:type:value");
            }
            return new Extra(text.substring(0, first), text.substring(first + 1, second),
                    text.substring(second + 1));
        }

        /** The value as the type names it, or null when it does not parse. */
        public Object typed() {
            try {
                switch (type.charAt(0)) {
                    case 'i':
                        return Integer.valueOf(value.trim());
                    case 'l':
                        return Long.valueOf(value.trim());
                    case 'f':
                        return Float.valueOf(value.trim());
                    case 'd':
                        return Double.valueOf(value.trim());
                    case 'z':
                        return Boolean.valueOf(value.trim());
                    default:
                        return value;
                }
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }

        @Override
        public String toString() {
            return name + ":" + type + ":" + value;
        }
    }

    /** A parsed call: where it goes, what it asks for, and its extras. */
    public static final class Call {
        public final String uri;
        public final String method;
        public final List<Extra> extras;

        private Call(String uri, String method, List<Extra> extras) {
            this.uri = uri;
            this.method = method;
            this.extras = Collections.unmodifiableList(new ArrayList<>(extras));
        }

        /** The extras as the provider's typed getters expect them. */
        public Args args() {
            Args.Builder builder = Args.builder();
            for (Extra extra : extras) {
                Object typed = extra.typed();
                builder.put(extra.name, typed == null ? extra.value : typed);
            }
            return builder.build();
        }
    }

    /**
     * Parses one command line. Accepts the short form a test writes and the full
     * {@code adb [-s SERIAL] shell content call} form a script runs.
     */
    public static Call parse(String commandLine) {
        if (commandLine == null) {
            throw new IllegalArgumentException("no command line");
        }
        List<String> tokens = tokenize(commandLine);
        String uri = null;
        String method = null;
        List<Extra> extras = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if ("--uri".equals(token) && i + 1 < tokens.size()) {
                uri = tokens.get(++i);
            } else if ("--method".equals(token) && i + 1 < tokens.size()) {
                method = tokens.get(++i);
            } else if ("--extra".equals(token) && i + 1 < tokens.size()) {
                extras.add(Extra.parse(tokens.get(++i)));
            }
        }
        if (method == null) {
            throw new IllegalArgumentException("no --method in " + commandLine);
        }
        return new Call(uri, method, extras);
    }

    /** The command a caller would run, with values quoted when they contain a space. */
    public static String command(String uri, String method, Extra... extras) {
        StringBuilder out = new StringBuilder("content call");
        if (uri != null) {
            out.append(" --uri ").append(quote(uri));
        }
        out.append(" --method ").append(quote(method));
        if (extras != null) {
            for (Extra extra : extras) {
                out.append(" --extra ").append(quote(extra.toString()));
            }
        }
        return out.toString();
    }

    /**
     * Parses the output of a {@code content call}, tolerating lines before the
     * Result line (a linker warning, for instance).
     */
    public static Reply parseReply(String output) {
        if (output == null) {
            throw new IllegalArgumentException("no output");
        }
        Matcher match = RESULT.matcher(output);
        if (!match.find()) {
            throw new IllegalArgumentException("no Result: Bundle in "
                    + output.trim().substring(0, Math.min(500, output.trim().length())));
        }
        return Reply.of(Integer.parseInt(match.group(1)), match.group(2));
    }

    /**
     * Splits a {@code data} body: first {@code =} wins, values are trimmed and
     * the last occurrence of a key wins, like a shell reading the state file.
     */
    public static Map<String, String> fields(String data) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (data == null) {
            return fields;
        }
        for (String line : data.split("\\r?\\n", -1)) {
            int equals = line.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String key = line.substring(0, equals).trim();
            if (!key.isEmpty()) {
                fields.put(key, line.substring(equals + 1).trim());
            }
        }
        return fields;
    }

    /**
     * A small quote-aware splitter: {@code '...'} and {@code "..."} keep spaces,
     * and a backslash escapes the next character outside single quotes.
     */
    public static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == quote) {
                    quoted = false;
                } else {
                    token.append(ch);
                }
            } else if (ch == '\'' || ch == '"') {
                quoted = true;
                quote = ch;
            } else if (ch == '\\' && i + 1 < line.length()) {
                token.append(line.charAt(++i));
            } else if (Character.isWhitespace(ch)) {
                if (token.length() > 0) {
                    tokens.add(token.toString());
                    token.setLength(0);
                }
            } else {
                token.append(ch);
            }
        }
        if (token.length() > 0) {
            tokens.add(token.toString());
        }
        return tokens;
    }

    private static String quote(String value) {
        if (value == null || value.isEmpty()) {
            return "''";
        }
        if (value.indexOf(' ') < 0 && value.indexOf('\t') < 0
                && value.indexOf('\'') < 0 && value.indexOf('"') < 0) {
            return value;
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
