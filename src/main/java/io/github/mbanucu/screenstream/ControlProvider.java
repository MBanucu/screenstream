package io.github.mbanucu.screenstream;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.github.mbanucu.control.Args;
import io.github.mbanucu.control.Reply;

/**
 * The stream's synchronous control surface, for a shell and for automation.
 *
 * <p>A one-shot intent tells the app to start but not whether it did; a harness
 * then polls the state file, which recent Android does not make readable to every
 * consumer, and guesses the port. This provider is the answer to both: a
 * {@code content call} starts or stops the capture and returns only when the
 * answer is known, and the reply carries the port the stream actually bound.
 *
 * <pre>
 * adb shell content call --uri content://io.github.mbanucu.screenstream.control \
 *     --method status
 * adb shell content call --uri content://io.github.mbanucu.screenstream.control \
 *     --method grant
 * adb shell content call --uri content://io.github.mbanucu.screenstream.control \
 *     --method start --extra port:i:0 --extra encoded_port:i:0
 * adb shell content call --uri content://io.github.mbanucu.screenstream.control \
 *     --method stop
 * </pre>
 *
 * <p>The reply bundle is {@code code} (0 for a successful call, 400 for one that
 * could not do what it was asked) and {@code data}: one {@code key=value} line per
 * field, using the state file's vocabulary. {@code start} is synchronous: it
 * returns {@code state=streaming} and the bound port after the first composited
 * frame, or {@code state=failed} with a {@code why} line when the capture or its
 * port did not come up in time. It never reads the state file to find out.
 *
 * <p>A projection that was never granted is not a failure with an unknown cause:
 * {@code start} answers {@code state=waiting_for_grant}, {@code reason=dialog},
 * and the exact app-op command that would grant it; {@code grant} reports the same
 * before a start is attempted. Both carry {@code grant=yes|no|unknown} and
 * {@code grant_route=appop|dialog|none|unknown}, so a harness can skip a run on a
 * device that would record a black frame instead of guessing.
 *
 * <p>The methods are {@code status}, {@code grant}, {@code start}, {@code stop},
 * {@code record} and {@code recording} (which ends the recording and writes its
 * index). {@code grant} changes nothing: the app-op is set by adb, not by an app
 * that has no shell identity.
 *
 * <p>Only adb shell, root, system and the app itself may call it; that gate, the
 * reply format and the escaping live in {@code io.github.mbanucu.control} (the
 * {@code android-control-surface} AAR), so another app's provider is the same
 * code rather than a copy. This app has no token: its surface is for a shell.
 * A provider is used rather than a broadcast because the platform starts it on
 * demand, even for a fresh process after a reboot, which is what makes
 * {@code status} answer {@code state=stopped} without the app having been
 * launched first.
 */
public final class ControlProvider extends io.github.mbanucu.control.ControlProvider {

    public static final String AUTHORITY = "io.github.mbanucu.screenstream.control";

    /** Long enough for a person to answer the projection dialog, short enough to fail. */
    private static final int START_TIMEOUT_MS = 20_000;
    private static final int MAX_START_TIMEOUT_MS = 120_000;
    private static final long STOP_TIMEOUT_MS = 10_000;
    private static final long RECORD_TIMEOUT_MS = 10_000;
    private static final long POLL_MS = 25;

    /** What to do when there is no app-op route; the probe is an option, not a need. */
    private static final String DIALOG_FALLBACK =
            "accept the system dialog on the device, or enable an accessibility clicker "
                    + "that clicks Start now";

    /** Serialises start/stop/record so a second command sees the first one's result. */
    private static final Object CONTROL = new Object();

    @Override
    protected String usage() {
        return "status, grant, start, stop, record or recording";
    }

    @Override
    protected String refusal() {
        return "refused: the control surface is for adb shell, root, system or the app itself";
    }

    @Override
    protected Reply handle(String method, Args extras) {
        Context context = getContext();
        switch (method) {
            case "status":
                return Reply.of(0, withGrant(ScreenStream.report(), context));
            case "grant":
                return Reply.of(0, grant(context));
            case "start":
                synchronized (CONTROL) {
                    return start(context, extras);
                }
            case "stop":
                synchronized (CONTROL) {
                    return stop(context);
                }
            case "record":
                synchronized (CONTROL) {
                    return record(context);
                }
            case "recording":
                synchronized (CONTROL) {
                    return stopRecording();
                }
            default:
                // The base turns null into the same 400 unknown-method sentence
                // this provider has always returned.
                return null;
        }
    }

    /**
     * Starts a capture on the requested ports, or reports the one already running.
     *
     * <p>The projection is a token the system dialog issues, so the activity is
     * what starts the capture; this waits for the first composited frame rather
     * than for the state file to say streaming. A still screen still produces
     * composited frames, so it does not have to change to answer. A projection
     * that is live but silent - no compositor frame - is not called streaming.
     *
     * <p>With the {@code PROJECT_MEDIA} app-op allowed, the platform can issue the
     * token without showing the dialog. Where it still shows one, or where the op
     * is not set, the answer is {@code state=waiting_for_grant} after the timeout,
     * with the command that would grant it: the caller is never told a black
     * capture is a working stream.
     */
    private static Reply start(Context context, Args extras) {
        int port = extras.intValue(ScreenStream.EXTRA_PORT, ScreenStream.PORT);
        int encodedPort = extras.intValue(ScreenStream.EXTRA_ENCODED_PORT, EncodedStream.PORT);
        if (port < 0 || port > 65535) {
            return failed(context, port,
                    "port must be 0 (ephemeral) or 1..65535, not " + port);
        }
        if (encodedPort < 0 || encodedPort > 65535) {
            return failed(context, port, "encoded_port must be 0 (ephemeral) or 1..65535, not "
                    + encodedPort);
        }
        if (port != 0 && port == encodedPort) {
            return failed(context, port,
                    "the raw and encoded streams cannot share port " + port);
        }

        int scale = Math.max(1, extras.intValue(ScreenStream.EXTRA_SCALE, 1));
        int timeout = extras.intValue("timeout_ms", START_TIMEOUT_MS);
        timeout = Math.max(1000, Math.min(MAX_START_TIMEOUT_MS, timeout));
        long deadline = SystemClock.elapsedRealtime() + timeout;
        // The op in force before the dialog is asked for is what decides the
        // route, even if a shell changes it while the capture is coming up.
        String appOp = Grant.state(context);
        ScreenStream.clearStartFailure();

        // A running capture owns its ports; asking for another does not start a
        // second listener, it reports the one that is already serving. It is only
        // streaming once a frame has arrived, so a live-but-silent projection is
        // given the same timeout as a fresh start instead of being called a stream.
        if (ScreenStream.live()) {
            if (waitForFirstFrame(deadline)) {
                return Reply.of(0, withGrant(ScreenStream.report(), context));
            }
            String why = ScreenStream.live()
                    ? "the capture is live on port " + ScreenStream.port()
                            + " but no frame arrived within " + timeout + " ms"
                    : "the capture stopped before its first frame within " + timeout + " ms";
            return failed(context, ScreenStream.live() ? ScreenStream.port() : port, why);
        }

        Intent ask = new Intent(context, ScreenStreamActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ScreenStream.EXTRA_SCALE, scale)
                .putExtra(ScreenStream.EXTRA_PORT, port)
                .putExtra(ScreenStream.EXTRA_ENCODED_PORT, encodedPort);
        if (extras.booleanValue("record", false)) {
            ask.putExtra("record", true);
        }
        try {
            context.startActivity(ask);
        } catch (RuntimeException failed) {
            return failed(context, port,
                    "could not start the screen capture activity: " + failed);
        }

        while (SystemClock.elapsedRealtime() < deadline) {
            if (ScreenStream.live() && ScreenStream.framesLooked() > 0) {
                ScreenStream.noteGrantRoute(Grant.startRoute(appOp));
                return Reply.of(0, withGrant(ScreenStream.report(), context));
            }
            String failure = ScreenStream.startFailure();
            if (failure != null) {
                if (ScreenStream.grantPending()) {
                    return waitingForGrant(context, port, failure);
                }
                String body = ScreenStream.report();
                return Reply.of(400, body.startsWith("state=failed")
                        ? withGrant(body, context)
                        : withGrant(ScreenStream.failureReport(port, failure), context));
            }
            if (!sleep(POLL_MS)) {
                break;
            }
        }

        if (!ScreenStream.live()) {
            // No process, no failure line, no frame: the dialog is unanswered or
            // the platform ignored the app-op and is still waiting for one. Either
            // way the missing piece is a grant, not a broken capture.
            return waitingForGrant(context, port,
                    "no capture started within " + timeout
                            + " ms; the projection dialog was not answered");
        }
        return failed(context, ScreenStream.port(),
                "the capture is live on port " + ScreenStream.port()
                        + " but no frame arrived within " + timeout + " ms");
    }

    /** True once the running capture has produced a composited frame before the deadline. */
    private static boolean waitForFirstFrame(long deadline) {
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!ScreenStream.live()) {
                return false;
            }
            if (ScreenStream.framesLooked() > 0) {
                return true;
            }
            if (!sleep(POLL_MS)) {
                return false;
            }
        }
        return ScreenStream.live() && ScreenStream.framesLooked() > 0;
    }

    /** Ends the capture and waits for the service, and any recording, to finish. */
    private static Reply stop(Context context) {
        boolean wasRecording = ScreenStream.recording();
        File recording = ScreenStream.recordingFile();
        // onDestroy finalises a running recording and blocks until the index is
        // written, so stopping the service is the synchronous way to stop both.
        ScreenStream.stop(context);
        long deadline = SystemClock.elapsedRealtime() + STOP_TIMEOUT_MS;
        while (!ScreenStream.serviceStopped()) {
            if (SystemClock.elapsedRealtime() > deadline) {
                return Reply.of(400, ScreenStream.failureReport(ScreenStream.port(),
                        "the capture did not stop within " + STOP_TIMEOUT_MS + " ms"));
            }
            if (!sleep(POLL_MS)) {
                break;
            }
        }
        String body = ScreenStream.report();
        if (wasRecording && recording != null) {
            body = append(body, "recording_file", recording.getAbsolutePath());
        }
        return Reply.of(0, body);
    }

    /** Starts recording the running capture; a second call returns the file already open. */
    private static Reply record(Context context) {
        if (!ScreenStream.live()) {
            return Reply.of(400, "state=stopped\nerror=nothing is streaming; start it first");
        }
        if (ScreenStream.recording()) {
            return Reply.of(0, ScreenStream.report());
        }
        File directory = context.getExternalFilesDir("recordings");
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
            return Reply.of(400, appendError(ScreenStream.report(),
                    "no recordings directory on this device"));
        }
        String name = "screen-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date()) + ".mp4";
        File file = new File(directory, name);
        if (!ScreenStream.record(file)) {
            return Reply.of(400, appendError(ScreenStream.report(),
                    "could not start recording to " + file));
        }
        return Reply.of(0, ScreenStream.report());
    }

    /** Stops the recording and waits for the MP4 index, then names the file it wrote. */
    private static Reply stopRecording() {
        if (!ScreenStream.recording() && !ScreenStream.recordingFinalizing()) {
            return Reply.of(400, appendError(ScreenStream.report(), "not recording"));
        }
        ScreenStream.stopRecording();
        long deadline = SystemClock.elapsedRealtime() + RECORD_TIMEOUT_MS;
        while (ScreenStream.recording() || ScreenStream.recordingFinalizing()) {
            if (SystemClock.elapsedRealtime() > deadline) {
                return Reply.of(400, appendError(ScreenStream.report(),
                        "the recording did not finish within " + RECORD_TIMEOUT_MS + " ms"));
            }
            if (!sleep(POLL_MS)) {
                break;
            }
        }
        File file = ScreenStream.lastRecording();
        String body = ScreenStream.report();
        if (file != null) {
            body = append(body, "recording_file", file.getAbsolutePath());
        }
        return Reply.of(0, body);
    }

    /**
     * The grant in force, how it was obtained, and the commands to obtain or
     * revoke a headless one. It reads the app-op; it never sets it.
     */
    private static String grant(Context context) {
        String appOp = Grant.state(context);
        boolean live = ScreenStream.live();
        String state = live ? Grant.YES : appOp;
        String body = "grant=" + state + "\n"
                + "grant_route=" + Grant.route(appOp, live, ScreenStream.grantRoute()) + "\n"
                + "package=" + Grant.packageName(context) + "\n";
        if (!Grant.YES.equals(state)) {
            body = append(body, "reason", "dialog");
            body = append(body, "command", Grant.appOpCommand(context));
            body = append(body, "fallback", DIALOG_FALLBACK);
        }
        body = append(body, "revoke_command", Grant.revokeCommand(context));
        return body;
    }

    /** Adds the two fields every status and start answer carries. */
    private static String withGrant(String body, Context context) {
        String appOp = Grant.state(context);
        boolean live = ScreenStream.live();
        body = append(body, "grant", live ? Grant.YES : appOp);
        body = append(body, "grant_route",
                Grant.route(appOp, live, ScreenStream.grantRoute()));
        return body;
    }

    /** A start whose missing piece is the grant, with the command that supplies it. */
    private static Reply waitingForGrant(Context context, int port, String why) {
        String appOp = Grant.state(context);
        String body = ScreenStream.waitingReport(port, why);
        body = append(body, "reason", "dialog");
        // This is an observed outcome, not a prediction: the start did not get a
        // token. Report no usable grant even when the app-op is allowed but this
        // platform ignored it; appop= keeps the underlying setting visible.
        body = append(body, "grant", Grant.NO);
        body = append(body, "grant_route", "dialog");
        body = append(body, "appop", appOp);
        body = append(body, "command", Grant.appOpCommand(context));
        body = append(body, "fallback", DIALOG_FALLBACK);
        if (Grant.YES.equals(appOp)) {
            body = append(body, "detail", "the app-op is allowed, but this platform "
                    + "still asked for the projection dialog");
        }
        return Reply.of(400, body);
    }

    private static Reply failed(Context context, int port, String why) {
        return Reply.of(400, withGrant(ScreenStream.failureReport(port, why), context));
    }

    /** Keeps an extra field on one line so the reply stays parseable. */
    private static String append(String body, String key, String value) {
        String line = key + "=" + Reply.oneLine(value);
        return body.endsWith("\n") ? body + line + "\n" : body + "\n" + line + "\n";
    }

    private static String appendError(String body, String error) {
        return append(body, "error", error);
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
