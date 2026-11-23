package io.github.mbanucu.screenstream;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

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
 * <p>The methods are {@code status}, {@code start}, {@code stop}, {@code record}
 * and {@code recording} (which ends the recording and writes its index).
 *
 * <p>Only adb shell, root, system and the app itself may call it. A provider is
 * used rather than a broadcast because the platform starts it on demand, even for
 * a fresh process after a reboot, which is what makes {@code status} answer
 * {@code state=stopped} without the app having been launched first.
 */
public final class ControlProvider extends ContentProvider {

    public static final String AUTHORITY = "io.github.mbanucu.screenstream.control";

    private static final String TAG = "ScreenStream";
    private static final int UID_ROOT = 0;
    private static final int UID_SYSTEM = 1000;
    private static final int UID_SHELL = 2000;

    /** Long enough for a person to answer the projection dialog, short enough to fail. */
    private static final int START_TIMEOUT_MS = 20_000;
    private static final int MAX_START_TIMEOUT_MS = 120_000;
    private static final long STOP_TIMEOUT_MS = 10_000;
    private static final long RECORD_TIMEOUT_MS = 10_000;
    private static final long POLL_MS = 25;

    /** Serialises start/stop/record so a second command sees the first one's result. */
    private static final Object CONTROL = new Object();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        int sender = Binder.getCallingUid();
        Result result = handle(getContext(), sender, method,
                extras == null ? Bundle.EMPTY : extras);
        return reply(result.code, result.data);
    }

    static Result handle(Context context, int sender, String method, Bundle extras) {
        Bundle in = extras == null ? Bundle.EMPTY : extras;
        if (!allowed(sender)) {
            Log.w(TAG, "control: refused uid " + sender + " for " + method);
            return new Result(403, "error=refused: the control surface is for adb shell, "
                    + "root, system or the app itself");
        }
        if (method == null) {
            return new Result(400, "error=missing method; use status, start, stop, "
                    + "record or recording");
        }
        switch (method) {
            case "status":
                return new Result(0, ScreenStream.report());
            case "start":
                synchronized (CONTROL) {
                    return start(context, in);
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
                return new Result(400, "error=unknown method " + ScreenStream.oneLine(method)
                        + "; use status, start, stop, record or recording");
        }
    }

    /** Shell, root, system and this app's own uid; no token, this app has no secrets. */
    private static boolean allowed(int uid) {
        return uid == UID_ROOT || uid == UID_SYSTEM || uid == UID_SHELL
                || uid == Process.myUid();
    }

    /**
     * Starts a capture on the requested ports, or reports the one already running.
     *
     * <p>The projection is a token the system dialog issues, so the activity is
     * what starts the capture; this waits for the first composited frame rather
     * than for the state file to say streaming. A still screen still produces
     * composited frames, so it does not have to change to answer.
     */
    private static Result start(Context context, Bundle extras) {
        int port = intExtra(extras, ScreenStream.EXTRA_PORT, ScreenStream.PORT);
        int encodedPort = intExtra(extras, ScreenStream.EXTRA_ENCODED_PORT, EncodedStream.PORT);
        if (port < 0 || port > 65535) {
            return failed(port, "port must be 0 (ephemeral) or 1..65535, not " + port);
        }
        if (encodedPort < 0 || encodedPort > 65535) {
            return failed(port, "encoded_port must be 0 (ephemeral) or 1..65535, not "
                    + encodedPort);
        }
        if (port != 0 && port == encodedPort) {
            return failed(port, "the raw and encoded streams cannot share port " + port);
        }
        // A running capture owns its ports; asking for another does not start a
        // second listener, it reports the one that is already serving.
        if (ScreenStream.live()) {
            return new Result(0, ScreenStream.report());
        }

        int scale = Math.max(1, intExtra(extras, ScreenStream.EXTRA_SCALE, 1));
        int timeout = intExtra(extras, "timeout_ms", START_TIMEOUT_MS);
        timeout = Math.max(1000, Math.min(MAX_START_TIMEOUT_MS, timeout));
        ScreenStream.clearStartFailure();

        Intent ask = new Intent(context, ScreenStreamActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ScreenStream.EXTRA_SCALE, scale)
                .putExtra(ScreenStream.EXTRA_PORT, port)
                .putExtra(ScreenStream.EXTRA_ENCODED_PORT, encodedPort);
        if (booleanExtra(extras, "record", false)) {
            ask.putExtra("record", true);
        }
        try {
            context.startActivity(ask);
        } catch (RuntimeException failed) {
            return failed(port, "could not start the screen capture activity: " + failed);
        }

        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ScreenStream.live() && ScreenStream.framesLooked() > 0) {
                return new Result(0, ScreenStream.report());
            }
            String failure = ScreenStream.startFailure();
            if (failure != null) {
                String body = ScreenStream.report();
                return new Result(400, body.startsWith("state=failed")
                        ? body : ScreenStream.failureReport(port, failure));
            }
            if (!sleep(POLL_MS)) {
                break;
            }
        }

        boolean live = ScreenStream.live();
        String why = live
                ? "the capture is live on port " + ScreenStream.port()
                        + " but no frame arrived within " + timeout + " ms"
                : "the screen capture did not start within " + timeout
                        + " ms; the projection dialog was not answered or the capture "
                        + "activity could not be started";
        return new Result(400, ScreenStream.failureReport(
                live ? ScreenStream.port() : port, why));
    }

    /** Ends the capture and waits for the service, and any recording, to finish. */
    private static Result stop(Context context) {
        boolean wasRecording = ScreenStream.recording();
        File recording = ScreenStream.recordingFile();
        // onDestroy finalises a running recording and blocks until the index is
        // written, so stopping the service is the synchronous way to stop both.
        ScreenStream.stop(context);
        long deadline = SystemClock.elapsedRealtime() + STOP_TIMEOUT_MS;
        while (!ScreenStream.serviceStopped()) {
            if (SystemClock.elapsedRealtime() > deadline) {
                return new Result(400, ScreenStream.failureReport(ScreenStream.port(),
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
        return new Result(0, body);
    }

    /** Starts recording the running capture; a second call returns the file already open. */
    private static Result record(Context context) {
        if (!ScreenStream.live()) {
            return new Result(400, "state=stopped\nerror=nothing is streaming; start it first");
        }
        if (ScreenStream.recording()) {
            return new Result(0, ScreenStream.report());
        }
        File directory = context.getExternalFilesDir("recordings");
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
            return new Result(400, appendError(ScreenStream.report(),
                    "no recordings directory on this device"));
        }
        String name = "screen-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date()) + ".mp4";
        File file = new File(directory, name);
        if (!ScreenStream.record(file)) {
            return new Result(400, appendError(ScreenStream.report(),
                    "could not start recording to " + file));
        }
        return new Result(0, ScreenStream.report());
    }

    /** Stops the recording and waits for the MP4 index, then names the file it wrote. */
    private static Result stopRecording() {
        if (!ScreenStream.recording() && !ScreenStream.recordingFinalizing()) {
            return new Result(400, appendError(ScreenStream.report(), "not recording"));
        }
        ScreenStream.stopRecording();
        long deadline = SystemClock.elapsedRealtime() + RECORD_TIMEOUT_MS;
        while (ScreenStream.recording() || ScreenStream.recordingFinalizing()) {
            if (SystemClock.elapsedRealtime() > deadline) {
                return new Result(400, appendError(ScreenStream.report(),
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
        return new Result(0, body);
    }

    private static Result failed(int port, String why) {
        return new Result(400, ScreenStream.failureReport(port, why));
    }

    /** Keeps an extra field on one line so the reply stays parseable. */
    private static String append(String body, String key, String value) {
        String line = key + "=" + ScreenStream.oneLine(value);
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

    /**
     * Reads a control extra as an int. {@code content call --extra port:i:0}
     * arrives as an Integer; a string is accepted as well so a caller that writes
     * {@code --extra port:s:0} is not silently given the default.
     */
    private static int intExtra(Bundle extras, String name, int fallback) {
        Object value = extras.get(name);
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

    private static boolean booleanExtra(Bundle extras, String name, boolean fallback) {
        Object value = extras.get(name);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean(((String) value).trim());
        }
        return fallback;
    }

    private static Bundle reply(int code, String data) {
        Bundle out = new Bundle();
        out.putInt("code", code);
        out.putString("data", data);
        return out;
    }

    static final class Result {
        final int code;
        final String data;

        Result(int code, String data) {
            this.code = code;
            this.data = data;
        }
    }

    // A provider must implement these; the surface is call(), not a table.
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
