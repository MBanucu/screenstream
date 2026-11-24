package io.github.mbanucu.control;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The gate and dispatch shared by {@link ControlProvider} and
 * {@link ControlReceiver}.
 *
 * <p>Order matters, and it is the order both existing providers already use:
 *
 * <ol>
 *   <li>the uid allowlist decides who is privileged; root, system, shell and the
 *       app itself need nothing else, and every command that worked before
 *       keeps working;
 *   <li>an ordinary app may present a token, which is verified in constant time;
 *   <li>a missing and a wrong token take the same path and produce the same 403;
 *   <li>only a privileged caller may mint a token (the shell-only method);
 *   <li>a method that is missing or unknown is 400, with the usage the provider
 *       configured;
 *   <li>the provider's {@link Handler} sees everything else.
 * </ol>
 *
 * <p>This class is plain Java: the gate, the mint/verify hand-off and the reply
 * text are unit-tested on the JVM.
 */
public final class ControlSurface {

    /** The method that mints a token, when a token is configured. */
    public static final String DEFAULT_TOKEN_METHOD = ControlContract.TOKEN;

    /** What a provider says when a caller is not allowed at all. */
    public static final String DEFAULT_REFUSAL =
            "refused: control needs adb shell, root, system or a valid control token";

    public interface Handler {
        /**
         * Runs one method.
         *
         * @return the reply, or null for a method this provider does not know
         */
        Reply handle(String method, Args args);
    }

    private final UidGate gate;
    private final ControlToken token;
    private final Handler handler;
    private final String tokenMethod;
    private final Set<String> shellOnly;
    private final String usage;
    private final String refusal;

    private ControlSurface(Builder builder) {
        this.gate = builder.gate;
        this.token = builder.token;
        this.handler = builder.handler;
        this.tokenMethod = builder.tokenMethod;
        this.shellOnly = Collections.unmodifiableSet(new LinkedHashSet<>(builder.shellOnly));
        this.usage = builder.usage;
        this.refusal = builder.refusal;
    }

    public static Builder builder(Handler handler) {
        return new Builder(handler);
    }

    public UidGate gate() {
        return gate;
    }

    public ControlToken token() {
        return token;
    }

    public String tokenMethod() {
        return tokenMethod;
    }

    public Set<String> shellOnlyMethods() {
        return shellOnly;
    }

    /**
     * The whole gate: what the provider's {@code call} and the receiver's
     * {@code onReceive} hand their caller and extras to.
     *
     * @param callerUid the Binder caller's uid, or a negative value when the
     *                  transport cannot prove one (a broadcast)
     */
    public Reply dispatch(int callerUid, String method, Args args) {
        Args in = args == null ? Args.EMPTY : args;
        boolean privileged = gate.privileged(callerUid);
        boolean tokenOk = !privileged && token != null
                && token.accept(in.string(ControlContract.TOKEN));
        if (!privileged && !tokenOk) {
            return Reply.forbidden(refusal);
        }
        if (method == null) {
            return Reply.badRequest("missing method" + hint());
        }
        if (!privileged && shellOnly.contains(method)) {
            return Reply.forbidden("refused: " + Reply.oneLine(method)
                    + " may only be requested from adb shell, root or system");
        }
        if (token != null && tokenMethod.equals(method)) {
            return mintToken();
        }
        Reply reply;
        try {
            reply = handler.handle(method, in);
        } catch (RuntimeException failed) {
            return Reply.keyValue(ControlContract.INTERNAL,
                    "error", "the control method failed: " + failed);
        }
        if (reply == null) {
            return Reply.badRequest("unknown method " + Reply.oneLine(method) + hint());
        }
        return reply;
    }

    private Reply mintToken() {
        try {
            String hex = token.issue();
            String path = token.describe();
            if (path == null || path.isEmpty()) {
                return Reply.keyValue(ControlContract.OK,
                        ControlContract.TOKEN_VALUE, hex);
            }
            return Reply.keyValue(ControlContract.OK,
                    ControlContract.TOKEN_VALUE, hex,
                    ControlContract.TOKEN_PATH, path);
        } catch (Exception failed) {
            return Reply.badRequest("could not mint a control token: " + failed.getMessage());
        }
    }

    private String hint() {
        return usage == null || usage.isEmpty() ? "" : "; use " + usage;
    }

    public static final class Builder {
        private final Handler handler;
        private UidGate gate = UidGate.defaults(-1);
        private ControlToken token;
        private String tokenMethod = DEFAULT_TOKEN_METHOD;
        private Set<String> shellOnly = new LinkedHashSet<>();
        private String usage;
        private String refusal = DEFAULT_REFUSAL;

        private Builder(Handler handler) {
            if (handler == null) {
                throw new IllegalArgumentException("a handler is required");
            }
            this.handler = handler;
            this.shellOnly.add(DEFAULT_TOKEN_METHOD);
        }

        public Builder gate(UidGate value) {
            this.gate = value;
            return this;
        }

        public Builder token(ControlToken value) {
            this.token = value;
            return this;
        }

        public Builder tokenMethod(String value) {
            this.tokenMethod = value;
            if (value != null) {
                this.shellOnly.add(value);
            }
            return this;
        }

        /** Replaces the shell-only set; the token method is always included. */
        public Builder shellOnly(String... methods) {
            this.shellOnly = new LinkedHashSet<>();
            if (methods != null) {
                for (String method : methods) {
                    if (method != null) {
                        this.shellOnly.add(method);
                    }
                }
            }
            if (tokenMethod != null) {
                this.shellOnly.add(tokenMethod);
            }
            return this;
        }

        public Builder usage(String value) {
            this.usage = value;
            return this;
        }

        /** The sentence after {@code error=} in a 403. */
        public Builder refusal(String value) {
            this.refusal = value == null ? DEFAULT_REFUSAL : value;
            return this;
        }

        public ControlSurface build() {
            return new ControlSurface(this);
        }
    }
}
