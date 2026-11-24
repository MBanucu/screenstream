package io.github.mbanucu.screenstream;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Process;
import android.util.Log;

/**
 * Whether a MediaProjection grant is already in force, and how a capture got one.
 *
 * <p>Capturing the display is normally a dialog. On a test device the platform
 * also has an app-op, {@code android:project_media}, that a shell can set before
 * a run:
 *
 * <pre>
 * adb shell appops set --uid your.package PROJECT_MEDIA allow
 * adb shell appops set --uid your.package PROJECT_MEDIA default   # revoke
 * </pre>
 *
 * <p>Where the platform honours it, the dialog is skipped and a harness can start
 * a capture with no tap. The op is not a documented third-party API and a grant
 * persists across cold starts, so it is opt-in per run on a test device, never
 * something the app does for itself. This class only reads the op; nothing here
 * writes it, and installing the app grants nothing.
 *
 * <p>{@link #state} is what the control surface reports as
 * {@code grant=yes|no|unknown}: {@code yes} for a standing app-op, {@code no}
 * when the app has no such grant, and {@code unknown} when the platform cannot be
 * asked. {@link #route} says which path a capture took - {@code appop} for one
 * the op let through, {@code dialog} for one a person or a clicker accepted.
 */
final class Grant {

    static final String YES = "yes";
    static final String NO = "no";
    static final String UNKNOWN = "unknown";

    private static final String TAG = "ScreenStream";
    /**
     * AppOpsManager's op string. The SDK exposes no constant for it, so it is
     * spelled the way the platform registers it.
     */
    private static final String PROJECT_MEDIA = "android:project_media";

    private Grant() {
    }

    /** {@code yes}, {@code no} or {@code unknown}, from the app-op for this app. */
    static String state(Context context) {
        AppOpsManager manager = context.getSystemService(AppOpsManager.class);
        if (manager == null) {
            return UNKNOWN;
        }
        try {
            int mode = manager.checkOpNoThrow(PROJECT_MEDIA, Process.myUid(),
                    context.getPackageName());
            switch (mode) {
                case AppOpsManager.MODE_ALLOWED:
                    return YES;
                case AppOpsManager.MODE_ERRORED:
                case AppOpsManager.MODE_IGNORED:
                case AppOpsManager.MODE_DEFAULT:
                    return NO;
                default:
                    return UNKNOWN;
            }
        } catch (RuntimeException unavailable) {
            // Some builds refuse the op to an app asking about itself; an answer
            // that is not known is better than one that claims a grant.
            Log.w(TAG, "could not read " + PROJECT_MEDIA, unavailable);
            return UNKNOWN;
        }
    }

    /**
     * How a capture was granted: {@code appop}, {@code dialog}, or {@code unknown}.
     *
     * <p>An app-op in force before the capture is the recorded route when the
     * platform skipped the dialog; no app-op means a dialog was accepted. On a
     * build that still shows the dialog with the op set, the route is
     * {@code appop} even though a person answered it: the platform's choice is not
     * something the app can see.
     */
    static String startRoute(String appOp) {
        if (YES.equals(appOp)) {
            return "appop";
        }
        if (NO.equals(appOp)) {
            return "dialog";
        }
        return UNKNOWN;
    }

    /**
     * The route to report now: the one the running capture recorded, or the path
     * a start would take.
     */
    static String route(String appOp, boolean live, String recorded) {
        if (live) {
            if (recorded != null) {
                return recorded;
            }
            if (YES.equals(appOp)) {
                return "appop";
            }
            if (NO.equals(appOp)) {
                return "dialog";
            }
            return UNKNOWN;
        }
        if (YES.equals(appOp)) {
            return "appop";
        }
        if (NO.equals(appOp)) {
            return "none";
        }
        return UNKNOWN;
    }

    static String packageName(Context context) {
        return context.getPackageName();
    }

    /**
     * The exact command that turns the dialog into a headless grant, run once.
     * {@code --uid} is load-bearing: project_media is a security app-op, and the
     * platform checks the UID mode, not the per-package mode.
     */
    static String appOpCommand(Context context) {
        return "adb shell appops set --uid " + packageName(context)
                + " PROJECT_MEDIA allow";
    }

    /** The command that puts the app back to asking through the dialog. */
    static String revokeCommand(Context context) {
        return "adb shell appops set --uid " + packageName(context)
                + " PROJECT_MEDIA default";
    }
}
