package io.github.mbanucu.screenstream;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

/**
 * Asks for the screen, and hands the answer to {@link ScreenStream}.
 *
 * <p>Capturing the display is not a permission an app can hold: the system puts a
 * dialog in front of it every time, and only a person - or something standing in
 * for one - can accept. This activity is that person's side of the flow: it asks,
 * and then either the dialog is answered or {@link ScreenStream#setDialogClicker}
 * was given something that answers it. Start it from adb:
 *
 * <pre>
 * am start -n your.package/io.github.mbanucu.screenstream.ScreenStreamActivity
 * </pre>
 *
 * <p>Extras: {@code --ei scale 2} captures at half size, which is what a machine
 * on the far side of a wireless link wants; {@code --ei port 0} and {@code --ei
 * encoded_port 0} ask for ports the kernel chooses instead of 7100 and 7101;
 * {@code --ez record true} also records the capture to an MP4 in the app's {@code
 * recordings} directory; {@code --ez stop true} ends the recording and the
 * capture, without touching anything else the app has bound.
 *
 * <p>It is the activity to drive from adb because each {@code am start} makes a
 * new instance of it: a running activity that is started again gets the intent as
 * {@code onNewIntent}, which is not a path a command can rely on.
 */
public final class ScreenStreamActivity extends Activity {

    private static final String TAG = "ScreenStream";
    private static final int REQUEST_CAPTURE = 1;
    /** How long to wait for the service to be capturing before recording. */
    private static final long RECORD_WAIT_MS = 5000;

    private boolean recordAfterGrant;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // `--ez stop true` ends a capture that is already running. Stopping the
        // service from here rather than force-stopping the app is deliberate: an
        // app that is also an accessibility service keeps that binding, so a run
        // that ends this way does not cost the next one a rebind.
        if (getIntent().getBooleanExtra("stop", false)) {
            ScreenStream.stopRecording();
            ScreenStream.stop(this);
            finish();
            return;
        }
        recordAfterGrant = getIntent().getBooleanExtra("record", false);
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        if (manager == null) {
            fail("no MediaProjectionManager on this device");
            return;
        }
        Intent capture;
        if (Build.VERSION.SDK_INT >= 34) {
            // The whole display, not one app: what a test records is the phone's
            // screen, including whatever dialog is in front of it.
            capture = manager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay());
        } else {
            capture = manager.createScreenCaptureIntent();
        }
        startActivityForResult(capture, REQUEST_CAPTURE);
        // The system dialog is another window, out of this process's reach.
        Runnable clicker = ScreenStream.dialogClicker();
        if (clicker != null) {
            clicker.run();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request != REQUEST_CAPTURE) {
            super.onActivityResult(request, result, data);
            return;
        }
        if (result != RESULT_OK || data == null) {
            fail("the screen capture dialog was not accepted");
            return;
        }
        // A machine on the far side of adb cannot take 18 MB a frame over a
        // wireless link; --ei scale 2 makes the compositor hand over half-size
        // frames instead of this class downscaling them afterwards.
        int scale = Math.max(1, getIntent().getIntExtra(ScreenStream.EXTRA_SCALE, 1));
        int port = getIntent().hasExtra(ScreenStream.EXTRA_PORT)
                ? getIntent().getIntExtra(ScreenStream.EXTRA_PORT, ScreenStream.PORT)
                : ScreenStream.PORT;
        int encodedPort = getIntent().hasExtra(ScreenStream.EXTRA_ENCODED_PORT)
                ? getIntent().getIntExtra(ScreenStream.EXTRA_ENCODED_PORT, EncodedStream.PORT)
                : EncodedStream.PORT;
        ScreenStream.start(this, data, scale, port, encodedPort);
        Log.i(TAG, "screen capture granted; the stream service is starting on port " + port);
        finish();
        if (recordAfterGrant) {
            recordAfterGrant = false;
            recordWhenLive(System.currentTimeMillis() + RECORD_WAIT_MS);
        }
    }

    /** The service starts a frame later than the grant; a recording waits for it. */
    private void recordWhenLive(final long deadline) {
        if (ScreenStream.live()) {
            startRecording();
            return;
        }
        if (System.currentTimeMillis() > deadline) {
            fail("the capture did not start in time to record it");
            return;
        }
        new android.os.Handler(getMainLooper()).postDelayed(
                () -> recordWhenLive(deadline), 100);
    }

    /** Opens a file in the app's recordings directory and starts writing to it. */
    private void startRecording() {
        java.io.File directory = getExternalFilesDir("recordings");
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
            fail("no recordings directory on this device");
            return;
        }
        String name = "screen-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",
                java.util.Locale.US).format(new java.util.Date()) + ".mp4";
        java.io.File file = new java.io.File(directory, name);
        Log.i(TAG, ScreenStream.record(file)
                ? "recording to " + file
                : "could not start recording to " + file);
    }

    private void fail(String why) {
        ScreenStream.noteStartFailure(why);
        Status.replace(this, ScreenStream.STATUS_FILE, "state=failed\nwhy="
                + ScreenStream.oneLine(why) + "\n");
        Log.w(TAG, why);
        finish();
    }
}
