package io.github.mbanucu.control;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

import java.util.Collections;
import java.util.Set;

/**
 * The {@link ContentProvider} half of a control surface.
 *
 * <p>An app subclasses this, implements {@link #handle(String, Args)} and
 * declares the provider in its manifest. The base does the parts every such
 * surface has repeated: the {@code code}/{@code data} reply, the uid allowlist,
 * the optional token, and the stubs a provider must implement but does not use.
 *
 * <pre>
 * public final class ControlProvider extends ControlProvider {
 *     {@literal @Override} protected String usage() { return "status, enable"; }
 *     {@literal @Override} protected Reply handle(String method, Args extras) {
 *         if ("status".equals(method)) return Reply.of(0, "state=ready");
 *         return null;   // 400 unknown method, with the usage above
 *     }
 * }
 * </pre>
 *
 * <p>By default root (0), system (1000), shell (2000) and {@code Process.myUid()}
 * are allowed. Override {@link #gate()} to change that. Override {@link
 * #token()} to let an ordinary app call with {@code --extra token:s:...}; the
 * token method is shell-only, so a token holder cannot mint another.
 */
public abstract class ControlProvider extends ContentProvider {

    public static final String METHOD_EXTRA = "method";

    private volatile ControlSurface surface;

    /**
     * Runs one method; return null for a method this provider does not know.
     *
     * <p>{@code extras} is already typed: {@link Args#intValue} accepts the
     * Integer a {@code content call --extra port:i:0} sends and a String a
     * hand-written {@code --extra port:s:0} sends.
     */
    protected abstract Reply handle(String method, Args extras);

    /** Who may call without a token; the four standard identities by default. */
    protected UidGate gate() {
        return UidGate.defaults(Process.myUid());
    }

    /** The optional token, or null when this surface has no secret. */
    protected ControlToken token() {
        return null;
    }

    /** The shell-only method that mints a token; used when {@link #token()} is set. */
    protected String tokenMethod() {
        return ControlSurface.DEFAULT_TOKEN_METHOD;
    }

    /** Methods an ordinary app may not call even with a valid token. */
    protected Set<String> shellOnlyMethods() {
        return Collections.singleton(tokenMethod());
    }

    /** The methods after {@code use} in a 400; null means no hint. */
    protected String usage() {
        return null;
    }

    /** The sentence after {@code error=} in a 403. */
    protected String refusal() {
        return ControlSurface.DEFAULT_REFUSAL;
    }

    /** The configured surface, built once from the overridable methods above. */
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
                                    return ControlProvider.this.handle(method, args);
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
    public boolean onCreate() {
        return true;
    }

    @Override
    public final Bundle call(String method, String arg, Bundle extras) {
        Args args = args(extras, arg);
        return bundle(surface().dispatch(Binder.getCallingUid(), method, args));
    }

    /** The reply as the Bundle {@code content call} prints. */
    public static Bundle bundle(Reply reply) {
        Bundle out = new Bundle();
        out.putInt(ControlContract.CODE, reply.code);
        out.putString(ControlContract.DATA, reply.data);
        return out;
    }

    /** A Bundle's values as typed Args, with a positional {@code arg} made available. */
    static Args args(Bundle extras, String arg) {
        Args.Builder builder = Args.builder();
        if (extras != null) {
            for (String key : extras.keySet()) {
                builder.put(key, extras.get(key));
            }
        }
        if (arg != null && (extras == null || !extras.containsKey(Args.ARG))) {
            builder.put(Args.ARG, arg);
        }
        return builder.build();
    }

    // The surface is call(), not a table; these exist because a provider must
    // implement them.
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
