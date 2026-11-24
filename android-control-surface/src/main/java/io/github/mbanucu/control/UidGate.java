package io.github.mbanucu.control;

/**
 * Who may call the control surface.
 *
 * <p>The defaults are the four identities every adb-driven provider in this
 * family has used: root (uid 0), system (1000), shell (2000) and the app itself
 * ({@code Process.myUid()}). An ordinary app is not in the set; it reaches the
 * surface through a token ({@link ControlSurface}) or the ordered-broadcast
 * twin ({@link ControlReceiver}).
 *
 * <p>This is plain Java so the gate is tested without a device. The Android
 * provider passes {@code Binder.getCallingUid()}; the broadcast path passes a
 * negative uid, which is never privileged.
 */
public final class UidGate {

    public static final int ROOT = 0;
    public static final int SYSTEM = 1000;
    public static final int SHELL = 2000;

    private final int selfUid;
    private final boolean allowRoot;
    private final boolean allowSystem;
    private final boolean allowShell;
    private final boolean allowSelf;

    private UidGate(int selfUid, boolean allowRoot, boolean allowSystem,
                    boolean allowShell, boolean allowSelf) {
        this.selfUid = selfUid;
        this.allowRoot = allowRoot;
        this.allowSystem = allowSystem;
        this.allowShell = allowShell;
        this.allowSelf = allowSelf;
    }

    /** The four standard identities, all allowed. */
    public static UidGate defaults(int selfUid) {
        return builder(selfUid).build();
    }

    public static Builder builder(int selfUid) {
        return new Builder(selfUid);
    }

    /** True for a caller that does not need a token. A negative uid never is. */
    public boolean privileged(int uid) {
        if (uid < 0) {
            return false;
        }
        if (allowRoot && uid == ROOT) {
            return true;
        }
        if (allowSystem && uid == SYSTEM) {
            return true;
        }
        if (allowShell && uid == SHELL) {
            return true;
        }
        return allowSelf && uid == selfUid;
    }

    public int selfUid() {
        return selfUid;
    }

    public boolean allowsSelf() {
        return allowSelf;
    }

    public static final class Builder {
        private final int selfUid;
        private boolean allowRoot = true;
        private boolean allowSystem = true;
        private boolean allowShell = true;
        private boolean allowSelf = true;

        private Builder(int selfUid) {
            this.selfUid = selfUid;
        }

        public Builder allowRoot(boolean allow) {
            this.allowRoot = allow;
            return this;
        }

        public Builder allowSystem(boolean allow) {
            this.allowSystem = allow;
            return this;
        }

        public Builder allowShell(boolean allow) {
            this.allowShell = allow;
            return this;
        }

        public Builder allowSelf(boolean allow) {
            this.allowSelf = allow;
            return this;
        }

        public UidGate build() {
            return new UidGate(selfUid, allowRoot, allowSystem, allowShell, allowSelf);
        }
    }
}
