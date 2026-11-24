package io.github.mbanucu.control;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;

import java.util.Collections;
import java.util.Set;

/**
 * The ordered-broadcast twin for an ordinary app uid.
 *
 * <p>{@code content call} looks like the obvious transport, but the
 * {@code content} binary asks for {@code ACCESS_CONTENT_PROVIDERS_EXTERNALLY},
 * a signature permission held only by shell and root. A call from Termux fails
 * inside {@code getContentProviderExternal()}, before a provider is entered, so
 * a token cannot help that path. An explicit ordered broadcast is allowed from
 * an app; this receiver runs the same dispatch and sets the reply as the
 * broadcast result for {@code am broadcast} to print.
 *
 * <p>A {@link BroadcastReceiver} does not expose a trustworthy sender uid on
 * every supported release, so this path does not pretend to have one: the token
 * is the whole gate, and a receiver with no token refuses every caller. Shell
 * and root keep their uid gate through {@link ControlProvider}.
 *
 * <p>Subclass it, implement {@link #action()}, {@link #handle(String, Args)}
 * and give it the same {@link #token()} as the provider.
 */
public abstract class ControlReceiver extends BroadcastReceiver {

    /** The extra naming the method; a {@code token} extra is read by the gate. */
    public static final String METHOD_EXTRA = ControlProvider.METHOD_EXTRA;

    private volatile ControlSurface surface;

    /** The broadcast action this receiver answers, e.g. {@code com.example.CONTROL}. */
    protected abstract String action();

    /** Runs one method; return null for a method this receiver does not know. */
    protected abstract Reply handle(String method, Args extras);

    protected UidGate gate() {
        return UidGate.defaults(Process.myUid());
    }

    protected ControlToken token() {
        return null;
    }

    protected String tokenMethod() {
        return ControlSurface.DEFAULT_TOKEN_METHOD;
    }

    protected Set<String> shellOnlyMethods() {
        return Collections.singleton(tokenMethod());
    }

    protected String usage() {
        return null;
    }

    protected String refusal() {
        return ControlSurface.DEFAULT_REFUSAL;
    }

    protected final ControlSurface surface() {
        ControlSurface current = surface;
        if (current == null) {
            synchronized (this) {
                current = surface;
                if (current == null) {
                    Set<String> shellOnly = shellOnlyMethods();
                    current = ControlSurface.builder(new ControlSurface.Handler() {
                                @Override
                                public Reply handle(String method, Args args) {
                                    return ControlReceiver.this.handle(method, args);
                                }
                            })
                            .gate(gate())
                            .token(token())
                            .tokenMethod(tokenMethod())
                            .shellOnly(shellOnly == null
                                    ? new String[0]
                                    : shellOnly.toArray(new String[0]))
                            .usage(usage())
                            .refusal(refusal())
                            .build();
                    surface = current;
                }
            }
        }
        return current;
    }

    @Override
    public final void onReceive(Context context, Intent intent) {
        String expected = action();
        if (intent == null || expected == null || !expected.equals(intent.getAction())) {
            return;
        }
        Bundle extras = intent.getExtras();
        String method = extras == null ? null : extras.getString(METHOD_EXTRA);
        // -1: the transport cannot prove a uid, so only the token can allow this.
        Reply reply = surface().dispatch(-1, method, ControlProvider.args(extras, null));
        // A plain broadcast has no reply channel; do not trip the platform's
        // "result during a non-ordered broadcast" warning to throw it away.
        if (isOrderedBroadcast()) {
            setResultCode(reply.code);
            setResultData(reply.data);
        }
    }
}
