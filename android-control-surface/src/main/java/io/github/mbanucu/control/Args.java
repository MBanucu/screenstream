package io.github.mbanucu.control;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The extras a provider's {@code handle} sees, with the typed getters its
 * {@code content call --extra name:type:value} caller expects.
 *
 * <p>An extra arrives as an Integer, Boolean, String and so on; a string is
 * accepted for an integer too, so {@code --extra port:s:0} is not silently
 * replaced by a default. A value that cannot be read as the requested type
 * yields the fallback rather than an exception: a control call should answer
 * with a sentence, not crash the provider.
 *
 * <p>This class is plain Java so the behaviour is tested without Android.
 */
public final class Args {

    /** An extra that reads the positional {@code call} argument, when one was used. */
    public static final String ARG = "arg";

    public static final Args EMPTY = builder().build();

    private final Map<String, Object> values;

    private Args(Map<String, Object> values) {
        this.values = values;
    }

    public static Args of(Map<String, ?> values) {
        Builder builder = builder();
        if (values != null) {
            for (Map.Entry<String, ?> entry : values.entrySet()) {
                builder.put(entry.getKey(), entry.getValue());
            }
        }
        return builder.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String string(String name) {
        Object value = values.get(name);
        return value == null ? null : String.valueOf(value);
    }

    public boolean has(String name) {
        return values.containsKey(name) && values.get(name) != null;
    }

    /** An int extra; a Number or a decimal string, otherwise {@code fallback}. */
    public int intValue(String name, int fallback) {
        Object value = values.get(name);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException ignored) {
                // Fall through: a string that is not a number is the default.
            }
        }
        return fallback;
    }

    /** A long extra; a Number or a decimal string, otherwise {@code fallback}. */
    public long longValue(String name, long fallback) {
        Object value = values.get(name);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ignored) {
                // Fall through.
            }
        }
        return fallback;
    }

    /**
     * A boolean extra: a Boolean, a Number (non-zero is true) or a string that
     * {@link Boolean#parseBoolean(String)} understands, otherwise {@code fallback}.
     */
    public boolean booleanValue(String name, boolean fallback) {
        Object value = values.get(name);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if ("true".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text)) {
                return false;
            }
        }
        return fallback;
    }

    public Map<String, Object> asMap() {
        return values;
    }

    public static final class Builder {
        private final Map<String, Object> values = new LinkedHashMap<>();

        public Builder put(String name, Object value) {
            values.put(name, value);
            return this;
        }

        public Args build() {
            return new Args(Collections.unmodifiableMap(new LinkedHashMap<>(values)));
        }
    }
}
