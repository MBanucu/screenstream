package io.github.mbanucu.screenstream.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.github.mbanucu.screenstream.ScreenStream;

/**
 * The stream, with buttons.
 *
 * <p>Everything here is a thin front for {@link ScreenStream}: the activity asks
 * for the display, starts and stops the capture, starts and stops a recording, and
 * shows what the service is doing. The service is what keeps working when this
 * activity is gone - a capture with a UI in front of it is the same capture a test
 * framework attaches to.
 *
 * <p>It also takes the same commands adb would give a headless phone, which is how
 * a test run records without touching the screen:
 *
 * <pre>
 * am start -n io.github.mbanucu.screenstream.app/.StreamUiActivity --ez record true
 * am start -n io.github.mbanucu.screenstream.app/.StreamUiActivity --ez stop true
 * </pre>
 *
 * <p>{@code --ez record true} grants nothing by itself: the system dialog still
 * asks, and an app-op or a dialog clicker is how a machine answers it.
 */
public final class StreamUiActivity extends Activity {

    private static final int REQUEST_CAPTURE = 1;
    private static final long TICK_MS = 500;

    private final Handler ticks = new Handler(Looper.getMainLooper());
    private TextView state, recordingPath;
    private Button start, stop, record, stopRecord;
    private CheckBox half;
    private boolean recordWhenLive;
    private long startedAt;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            show();
            ticks.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());

        start.setOnClickListener(view -> ask());
        stop.setOnClickListener(view -> {
            ScreenStream.stopRecording();
            ScreenStream.stop(this);
        });
        record.setOnClickListener(view -> beginRecording());
        stopRecord.setOnClickListener(view -> ScreenStream.stopRecording());

        handle(getIntent());
    }

    /**
     * adb starts this activity again to stop or to record, and a running activity
     * gets that as a new intent rather than a second onCreate: both paths lead
     * here, so a command means the same thing whether the screen is open or not.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        if (intent == null) {
            return;
        }
        android.util.Log.i("ScreenStream", "ui command stop=" + intent.getBooleanExtra("stop", false)
                + " record=" + intent.getBooleanExtra("record", false));
        if (intent.getBooleanExtra("stop", false)) {
            ScreenStream.stopRecording();
            ScreenStream.stop(this);
            finish();
            return;
        }
        if (intent.getIntExtra("scale", 0) > 0) {
            half.setChecked(intent.getIntExtra("scale", 1) > 1);
        }
        if (intent.getBooleanExtra("record", false)) {
            if (ScreenStream.live()) {
                beginRecording();
            } else {
                recordWhenLive = true;
                ask();
            }
        }
    }

    private ScrollView buildLayout() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        column.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Screen stream");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        column.addView(title);

        TextView about = new TextView(this);
        about.setText("Raw frames on 127.0.0.1:7100 for anything on the phone or "
                + "behind an adb forward, and an MP4 file of the same display.");
        about.setPadding(0, pad / 2, 0, pad);
        column.addView(about);

        state = new TextView(this);
        state.setTypeface(Typeface.MONOSPACE);
        state.setTextSize(13);
        state.setPadding(0, 0, 0, pad);
        column.addView(state);

        LinearLayout captureRow = row(column);
        start = button(captureRow, "Start");
        stop = button(captureRow, "Stop");

        LinearLayout recordRow = row(column);
        record = button(recordRow, "Record");
        stopRecord = button(recordRow, "Stop recording");

        half = new CheckBox(this);
        half.setText("Half size (half the pixels, a quarter of the bytes)");
        column.addView(half, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        recordingPath = new TextView(this);
        recordingPath.setTextSize(12);
        recordingPath.setTypeface(Typeface.MONOSPACE);
        recordingPath.setPadding(0, pad / 2, 0, 0);
        column.addView(recordingPath);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        return scroll;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.START);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Math.round(4 * getResources().getDisplayMetrics().density);
        parent.addView(row, params);
        return row;
    }

    private Button button(LinearLayout row, String label) {
        Button button = new Button(this);
        button.setText(label);
        row.addView(button, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return button;
    }

    private void ask() {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        if (manager == null) {
            recordingPath.setText("This device has no MediaProjectionManager.");
            return;
        }
        Intent capture = Build.VERSION.SDK_INT >= 34
                ? manager.createScreenCaptureIntent(
                        MediaProjectionConfig.createConfigForDefaultDisplay())
                : manager.createScreenCaptureIntent();
        startActivityForResult(capture, REQUEST_CAPTURE);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request != REQUEST_CAPTURE) {
            super.onActivityResult(request, result, data);
            return;
        }
        if (result != RESULT_OK || data == null) {
            recordWhenLive = false;
            recordingPath.setText("The screen capture dialog was not accepted.");
            return;
        }
        ScreenStream.start(this, data, half.isChecked() ? 2 : 1);
    }

    private void beginRecording() {
        if (!ScreenStream.live()) {
            recordWhenLive = true;
            ask();
            return;
        }
        File directory = getExternalFilesDir("recordings");
        if (directory == null) {
            recordingPath.setText("This device has no external files directory.");
            return;
        }
        if (!directory.isDirectory() && !directory.mkdirs()) {
            recordingPath.setText("Could not create " + directory);
            return;
        }
        String name = "screen-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date()) + ".mp4";
        File file = new File(directory, name);
        recordingPath.setText(ScreenStream.record(file)
                ? "Recording to " + file.getAbsolutePath()
                : "Could not start recording (see logcat).");
    }

    @Override
    protected void onResume() {
        super.onResume();
        ticks.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticks.removeCallbacks(refresh);
    }

    /** The buttons and numbers, from the service's own state. */
    private void show() {
        boolean live = ScreenStream.live();
        boolean recording = ScreenStream.recording();
        start.setEnabled(!live);
        stop.setEnabled(live);
        record.setEnabled(live && !recording);
        stopRecord.setEnabled(recording);
        half.setEnabled(!live);

        if (recordWhenLive && live && !recording) {
            recordWhenLive = false;
            beginRecording();
        }
        if (live && startedAt == 0) {
            startedAt = SystemClock.elapsedRealtime();
        } else if (!live) {
            startedAt = 0;
        }

        StringBuilder text = new StringBuilder();
        text.append("state      ").append(live ? "streaming" : "stopped").append('\n');
        if (live) {
            text.append("size       ").append(ScreenStream.captureWidth()).append('x')
                    .append(ScreenStream.captureHeight()).append('\n');
            text.append("frames     ").append(ScreenStream.frameNumber())
                    .append(" changed, ").append((SystemClock.elapsedRealtime() - startedAt) / 1000)
                    .append(" s\n");
            text.append("port       ").append(ScreenStream.port()).append('\n');
        }
        text.append("recording  ");
        if (recording) {
            long ms = ScreenStream.recordedMilliseconds();
            text.append(ScreenStream.recordedFrames()).append(" frames, ")
                    .append(ms / 1000).append('.').append((ms % 1000) / 100).append(" s\n");
            File file = ScreenStream.recordingFile();
            if (file != null && (recordingPath.getText().length() == 0
                    || !recordingPath.getText().toString().contains(file.getName()))) {
                recordingPath.setText(file.getAbsolutePath());
            }
        } else {
            text.append("no\n");
        }
        state.setText(text);
    }
}
