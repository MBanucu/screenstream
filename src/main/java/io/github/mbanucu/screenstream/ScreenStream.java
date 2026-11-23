package io.github.mbanucu.screenstream;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The phone's screen, as a raw stream that anything on the phone - or anything
 * holding adb - can attach to.
 *
 * <p>This is the source, not a recording: images come from the compositor as
 * RGBA_8888, straight out of an {@link ImageReader}, and go onto the wire without
 * an encoder or a container. Nothing is remuxed and nothing is keyframed, so a
 * consumer gets the frame the screen showed, not a picture of it.
 *
 * <p>Three properties are deliberate:
 *
 * <ul>
 *   <li><b>Raw.</b> Each frame is the display's pixels at its own row stride:
 *       {@code stride * height} bytes of RGBA, no compression, no timestamps
 *       baked into a codec's bitstream.</li>
 *   <li><b>VFR, only when the screen changes.</b> The compositor hands frames
 *       over at the display's rate whether or not anything moved. Every frame is
 *       compared with the one before it on a sample grid, and only the ones that
 *       differ are numbered and sent: a still screen costs nothing, a blinking
 *       caret costs two frames. This is the rule {@code screenrecord} applies to
 *       its video, so a gap between two frame numbers is idle time and not a lost
 *       frame.</li>
 *   <li><b>Timed on the device.</b> {@link Image#getTimestamp()} is the
 *       presentation time in nanoseconds on the device's elapsed clock, the clock
 *       {@code screenrecord}'s metadata track and this stream's own frame numbers
 *       are comparable with.</li>
 * </ul>
 *
 * <h2>Attaching</h2>
 *
 * <p>The source listens on {@code 127.0.0.1:7100}. An app connects to it
 * directly; adb reaches the same port through a forward, which is what makes the
 * stream visible on the other end of the cable:
 *
 * <pre>
 * adb forward tcp:7100 tcp:7100
 * python3 test/stream.py --frames 3
 * </pre>
 *
 * <p>Every frame starts with a 36-byte big-endian header, then its pixels:
 *
 * <pre>
 *   magic "RAW1"   4 bytes
 *   width          u32
 *   height         u32
 *   row stride     u32   bytes per row in the payload, padding included
 *   format         u32   1 = RGBA_8888, 4 bytes per pixel
 *   frame number   u64   changed frames since the capture began
 *   timestamp      u64   nanoseconds, the device's elapsed clock
 * </pre>
 *
 * <p>An app in the same process does not need the socket: {@link #subscribe}
 * hands it every changed frame, shared with the socket consumers, so it costs no
 * copy and no connection.
 *
 * <h2>Using it from another project</h2>
 *
 * <p>Two things have to happen outside this class, both of them an app's own
 * business: someone grants the capture, and the phone's app-op is left alone. The
 * grant is a {@link MediaProjection} token, which only the system dialog issues;
 * {@link ScreenStreamActivity} asks for it, and {@link #setDialogClicker} is how
 * something standing in for a person - an accessibility service, say - accepts
 * the dialog. The activity is started by component:
 *
 * <pre>
 * adb shell am start -n your.package/io.github.mbanucu.screenstream.ScreenStreamActivity
 * adb shell am start -n your.package/io.github.mbanucu.screenstream.ScreenStreamActivity \
 *     --ez stop true
 * </pre>
 *
 * <p>For automation, {@link ControlProvider} is the synchronous surface: a
 * {@code content call} reports the state, starts and stops the capture, and
 * returns the port it actually bound instead of leaving a caller to poll the
 * file. The service itself stays driven by the intents above.
 *
 * <p>What the service is doing is written to {@link #statusFile} in the app's own
 * external files directory, so it can be read over adb while the accessibility
 * service is off.
 */
public final class ScreenStream extends Service {

    /** The default port; a start may name another one, or 0 for an ephemeral one. */
    public static final int PORT = 7100;
    public static final String ACTION_START = "io.github.mbanucu.screenstream.START";
    public static final String ACTION_STOP = "io.github.mbanucu.screenstream.STOP";
    public static final String EXTRA_RESULT = "result";
    public static final String EXTRA_SCALE = "scale";
    /** The raw stream's port: the default unless a start named one, 0 = ephemeral. */
    public static final String EXTRA_PORT = "port";
    /** The encoded stream's port: the default unless a start named one, 0 = ephemeral. */
    public static final String EXTRA_ENCODED_PORT = "encoded_port";
    /** The state this service writes, in the app's external files directory. */
    public static final String STATUS_FILE = "screenstream.txt";

    private static final String TAG = "ScreenStream";
    /** Three frames in flight at the largest size this phone can produce. */
    private static final int POOL = 3;
    /** How long one frame may take to reach a consumer before it is cut off. */
    private static final long WRITE_DEADLINE_MS = 2000;
    /** Sample grid for "did the screen change": a caret is far bigger than this. */
    private static final int CHANGE_STEP = 8;
    private static final int HEADER_BYTES = 4 + 4 * 4 + 8 * 2;

    private MediaProjection projection;
    private VirtualDisplay display;
    private volatile Recorder recorder;
    /** The encoder that exists only while someone watches the encoded stream. */
    private volatile Recorder streamEncoder;
    private volatile EncodedStream encoded;
    private volatile boolean recordingFinalizing;
    private volatile long lastRecordedFrames, lastRecordedMs, lastRecordedFromNs;
    private ImageReader reader;
    private HandlerThread thread;
    private ServerSocketChannel server;
    private int width, height, scale = 1;
    /** The ports this capture actually bound. */
    private int port = PORT, encodedPort = EncodedStream.PORT;

    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private static final List<Tap> taps = new CopyOnWriteArrayList<>();
    private static final ArrayDeque<byte[]> pool = new ArrayDeque<>();
    private static int bufferSize;
    private static int allocated;
    /** True while a capture is running in this process, so a tap knows to wait. */
    private static volatile boolean live;
    private static volatile boolean running;
    private static volatile ScreenStream self;
    private static volatile String lastReport;
    private static volatile String lastFailure;
    private static volatile File lastFinishedRecording;
    private static long frameNumber;
    private static volatile Runnable dialogClicker;
    private long previousSignature;
    private long looked, changed, skipped, sent;
    private long lastStatus;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action) && intent.getParcelableExtra(EXTRA_RESULT) != null) {
            running = true;
            startForeground(1, notification());
            begin(intent.getParcelableExtra(EXTRA_RESULT), intent.getIntExtra(EXTRA_SCALE, 1),
                    intent.getIntExtra(EXTRA_PORT, PORT),
                    intent.getIntExtra(EXTRA_ENCODED_PORT, EncodedStream.PORT));
            return START_STICKY;
        }
        return START_NOT_STICKY;
    }

    /** Starts a capture with a token a {@link MediaProjectionManager} handed out. */
    public static void start(Context context, Intent projection, int scale) {
        start(context, projection, scale, PORT, EncodedStream.PORT);
    }

    /**
     * Starts a capture on the ports named, or returns to the defaults.
     *
     * <p>{@code port} and {@code encodedPort} may be 0, which asks the kernel for
     * a free one; {@link #port()} and {@link #encodedPort()} report what was bound.
     */
    public static void start(Context context, Intent projection, int scale,
                             int port, int encodedPort) {
        Intent service = new Intent(context, ScreenStream.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT, projection)
                .putExtra(EXTRA_SCALE, Math.max(1, scale))
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_ENCODED_PORT, encodedPort);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(service);
        } else {
            context.startService(service);
        }
    }

    /** Ends the capture; the notification and the port go with it. */
    public static void stop(Context context) {
        context.stopService(new Intent(context, ScreenStream.class));
    }

    /**
     * Writes the display to {@code file} as an H.264 MP4, beside the raw stream.
     *
     * <p>A projection allows one virtual display, so the recording cannot capture
     * the screen by itself: it encodes the frames the stream has already copied.
     * Time zero in the file is the first frame it encoded, whose time on the
     * stream's clock is the status file's {@code recording_from_ns}; the frames
     * after it are the stream's, minus the ones the encoder was too busy for.
     *
     * @return false when nothing is capturing, or a recording is already running
     */
    public static boolean record(File file) {
        ScreenStream stream = self;
        return stream != null && stream.startRecording(file);
    }

    /** Ends the recording and finalises the file; safe to call when there is none. */
    public static void stopRecording() {
        ScreenStream stream = self;
        if (stream != null) {
            stream.endRecording();
        }
    }

    public static boolean recording() {
        ScreenStream stream = self;
        return stream != null && stream.recorder != null;
    }

    /** The file being written, or null when nothing is recording. */
    public static File recordingFile() {
        ScreenStream stream = self;
        Recorder running = stream == null ? null : stream.recorder;
        return running == null ? null : running.file();
    }

    /** Frames the current recording has written, or the last one wrote. */
    public static long recordedFrames() {
        ScreenStream stream = self;
        if (stream == null) {
            return 0;
        }
        Recorder running = stream.recorder;
        return running == null ? stream.lastRecordedFrames : running.frames();
    }

    /** How long the recording runs so far, from the last frame the encoder wrote. */
    public static long recordedMilliseconds() {
        ScreenStream stream = self;
        if (stream == null) {
            return 0;
        }
        Recorder running = stream.recorder;
        return running == null ? stream.lastRecordedMs : running.milliseconds();
    }

    /** Frame size of the running capture, or 0 when there is none. */
    public static int captureWidth() {
        ScreenStream stream = self;
        return stream == null ? 0 : stream.width;
    }

    public static int captureHeight() {
        ScreenStream stream = self;
        return stream == null ? 0 : stream.height;
    }

    /** The port the raw stream is serving, or the default while nothing is. */
    public static int port() {
        ScreenStream stream = self;
        return stream == null ? PORT : stream.port;
    }

    /** The encoded stream's bound port, or -1 when it is not serving. */
    public static int encodedPort() {
        ScreenStream stream = self;
        return stream == null || stream.encoded == null ? -1 : stream.encoded.port();
    }

    /** Composited frames seen by the current capture; 0 when there is none. */
    static long framesLooked() {
        ScreenStream stream = self;
        return stream == null ? 0 : stream.looked;
    }

    /** Why the last start failed, or null if it did not. */
    static String startFailure() {
        return lastFailure;
    }

    /** Clears a previous start's failure before a new one is asked for. */
    static void clearStartFailure() {
        lastFailure = null;
    }

    /** Records a failure outside the service, e.g. a dialog that was not accepted. */
    static void noteStartFailure(String why) {
        lastFailure = why;
    }

    /** True once the service has finished stopping, including a recording's index. */
    static boolean serviceStopped() {
        return !running;
    }

    /**
     * The state as the provider replies, in the state file's key=value vocabulary.
     *
     * <p>This is an in-process answer, not a read of {@link #statusFile}: a fresh
     * process with no capture says stopped without launching anything. A service
     * that is gone leaves its last report, so a caller that stops it sees the
     * counters and the final recording rather than a default.
     */
    static String report() {
        ScreenStream stream = self;
        if (stream != null) {
            return stream.statusBody(live ? "streaming" : "stopped", null).toString();
        }
        String cached = lastReport;
        return cached != null ? cached : idleReport("stopped", null, PORT);
    }

    /** A full report for a start that never became a capture. */
    static String failureReport(int requestedPort, String why) {
        return idleReport("failed", why, requestedPort);
    }

    private static String idleReport(String state, String why, int port) {
        StringBuilder out = new StringBuilder();
        out.append("state=").append(state).append('\n');
        if (why != null) {
            out.append("why=").append(oneLine(why)).append('\n');
        }
        out.append("port=").append(port).append('\n');
        out.append("size=0x0\n");
        out.append("scale=1\n");
        out.append("format=rgba8888\n");
        out.append("frames_looked=0\n");
        out.append("frames_changed=0\n");
        out.append("frames_sent=0\n");
        out.append("frames_skipped=0\n");
        out.append("clients=0\n");
        out.append("taps=0\n");
        out.append("recording=-\n");
        out.append("recorded_frames=0\n");
        out.append("recorded_ms=0\n");
        out.append("recording_from_ns=0\n");
        out.append("encoded_port=-\n");
        out.append("encoded_clients=0\n");
        out.append("encoded_frames=0\n");
        out.append("encoded_bytes=0\n");
        out.append("encoder=-\n");
        return out.toString();
    }

    /** The recording that finished last, or null when none ever ran. */
    static File lastRecording() {
        return lastFinishedRecording;
    }

    /** True while a recording is being finalised after its last frame. */
    static boolean recordingFinalizing() {
        ScreenStream stream = self;
        return stream != null && stream.recordingFinalizing;
    }

    /** Keeps one field on one line so a reply stays parseable. */
    static String oneLine(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private synchronized boolean startRecording(File file) {
        if (!live || recorder != null) {
            return false;
        }
        try {
            recorder = Recorder.start(file, width, height);
            // One encoder for both consumers: a viewer that was watching the live
            // encoder moves to the recording's, so the picture and the file stay the
            // same capture instead of two encodings of it.
            if (encoded != null && encoded.clients() > 0) {
                Recorder live = streamEncoder;
                streamEncoder = null;
                encoded.attach(recorder);
                if (live != null) {
                    new Thread(live::stop, "screen-stream-encode-stop").start();
                }
            }
            status("streaming");
            Log.i(TAG, "recording to " + file);
            return true;
        } catch (IOException | RuntimeException failed) {
            Log.w(TAG, "could not start recording", failed);
            return false;
        }
    }

    /** Stops feeding the recorder and lets it finish in the background. */
    private void endRecording() {
        Recorder ending = detachRecording();
        if (ending == null) {
            return;
        }
        new Thread(() -> finishRecording(ending), "screen-stream-record-stop").start();
        // A viewer must not stop watching because the recording stopped: it gets a
        // live encoder of its own again, from the next frame on.
        if (encoded != null && encoded.clients() > 0) {
            ensureStreamEncoder();
        }
    }

    private synchronized Recorder detachRecording() {
        Recorder ending = recorder;
        if (ending == null) {
            return null;
        }
        recorder = null;
        // Set before the caller sees the recorder gone: a provider asked to stop
        // the recording must not answer while the MP4 index is still being written.
        recordingFinalizing = true;
        lastRecordedFrames = ending.frames();
        lastRecordedMs = ending.milliseconds();
        lastRecordedFromNs = Math.max(0, ending.startedAtUs()) * 1000;
        if (ending.file() != null) {
            lastFinishedRecording = ending.file();
        }
        return ending;
    }

    /** Blocks until the encoder has written its last frames and the file's index. */
    private void finishRecording(Recorder ending) {
        try {
            ending.stop();
            lastRecordedFrames = ending.frames();
            lastRecordedMs = ending.milliseconds();
            if (ending.failure() != null) {
                Log.w(TAG, "recording ended with: " + ending.failure());
            }
            Log.i(TAG, "recording finished: " + lastRecordedFrames + " frames, "
                    + lastRecordedMs + " ms");
            // The recording outlives the capture it was started with: this runs on
            // its own thread and may land after the service has stopped, and a
            // "streaming" written then would describe a service that is gone.
            status(live ? "streaming" : "stopped");
        } finally {
            recordingFinalizing = false;
        }
    }

    /** A viewer arrived or left; the encoder exists only while one is watching. */
    private synchronized void encodedClients(int count) {
        if (count > 0) {
            ensureStreamEncoder();
        } else {
            stopStreamEncoder();
        }
    }

    /**
     * Makes sure something is encoding for the viewers.
     *
     * <p>A recording already has an encoder and is the one to use: one encode feeds
     * the file and the socket, so they cannot show different pictures. With no
     * recording, an encoder with no file is started, and it stops when the last
     * viewer leaves.
     */
    private void ensureStreamEncoder() {
        if (encoded == null || !live) {
            return;
        }
        Recorder source = recorder;
        if (source == null) {
            source = streamEncoder;
            if (source == null) {
                try {
                    source = Recorder.start(null, width, height);
                } catch (IOException | RuntimeException failed) {
                    Log.w(TAG, "no encoder for the encoded stream", failed);
                    return;
                }
                streamEncoder = source;
            }
        }
        encoded.attach(source);
        source.requestSync();
    }

    private synchronized void stopStreamEncoder() {
        if (encoded != null) {
            encoded.attach(null);
        }
        Recorder ending = streamEncoder;
        streamEncoder = null;
        if (ending != null) {
            // Stopping waits for the encoder's last frames; not on this thread.
            new Thread(ending::stop, "screen-stream-encode-stop").start();
        }
    }

    /**
     * Something that can accept the system's capture dialog on this app's behalf.
     *
     * <p>The dialog is the one thing here that needs a person, and an
     * accessibility service is the one thing on the phone that can click it
     * without becoming one. An app with no such service leaves this alone and asks
     * a person.
     */
    public static void setDialogClicker(Runnable clicker) {
        dialogClicker = clicker;
    }

    /** The status file this service writes, for whoever polls it over adb. */
    public static File statusFile(Context context) {
        File directory = context.getExternalFilesDir(null);
        return directory == null ? null : new File(directory, STATUS_FILE);
    }

    private void begin(Intent result, int requestedScale, int requestedPort,
                       int requestedEncodedPort) {
        scale = Math.max(1, requestedScale);
        frameNumber = 0;
        lastFailure = null;
        port = requestedPort;
        encodedPort = requestedEncodedPort;
        if (requestedPort < 0 || requestedPort > 65535) {
            fail("port must be 0 (ephemeral) or 1..65535, not " + requestedPort);
            return;
        }
        if (requestedEncodedPort < 0 || requestedEncodedPort > 65535) {
            fail("encoded_port must be 0 (ephemeral) or 1..65535, not "
                    + requestedEncodedPort);
            return;
        }
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        if (manager == null) {
            fail("no MediaProjectionManager");
            return;
        }
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        width = Math.max(1, metrics.widthPixels / scale);
        height = Math.max(1, metrics.heightPixels / scale);
        projection = manager.getMediaProjection(Activity.RESULT_OK, result);
        if (projection == null) {
            fail("the projection token was not usable");
            return;
        }
        // API 34 refuses a VirtualDisplay whose projection has no callback, and a
        // person stopping the capture from the status bar has to end this too.
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.i(TAG, "the projection was stopped from outside");
                stopSelf();
            }
        }, new Handler(getMainLooper()));

        thread = new HandlerThread("screen-stream");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(source -> {
            // The newest image, so a slow copy drops frames instead of queueing
            // them: this is a live stream, not a recorder that must keep them all.
            Image image = source.acquireLatestImage();
            if (image != null) {
                onImage(image);
            }
        }, handler);
        display = projection.createVirtualDisplay("screen-stream", width, height,
                metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, handler);
        // A port that cannot be opened is not a capture: another stream on the
        // phone may already have it, and saying "streaming" would be a lie that a
        // UI and a recording would both act on.
        if (!listen(requestedPort)) {
            return;
        }
        // The encoded stream is an extra, not the capture: a port that cannot be
        // opened costs the raw stream nothing, so it is logged and left out.
        encoded = EncodedStream.open(requestedEncodedPort, this::encodedClients);
        encodedPort = encoded == null ? -1 : encoded.port();
        live = true;
        self = this;
        // A viewer that attached before the capture was marked live has no encoder
        // yet; give it one now rather than leaving it waiting for a keyframe.
        if (encoded != null && encoded.clients() > 0) {
            ensureStreamEncoder();
        }
        status("streaming");
    }

    /** Opens the port and keeps accepting; a consumer leaves by disconnecting. */
    private boolean listen(int requestedPort) {
        try {
            server = ServerSocketChannel.open();
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),
                    requestedPort));
            port = ((InetSocketAddress) server.getLocalAddress()).getPort();
        } catch (IOException failed) {
            // The requested port, not the field: a failed bind never reached the
            // point where the field is the actual one.
            fail("could not listen on 127.0.0.1:" + requestedPort + ": " + failed);
            return false;
        }
        new Thread(() -> {
            while (server.isOpen()) {
                try {
                    SocketChannel channel = server.accept();
                    if (channel == null) {
                        continue;
                    }
                    try {
                        java.net.Socket socket = channel.socket();
                        socket.setTcpNoDelay(true);
                        socket.setSendBufferSize(1 << 20);
                    } catch (IOException ignored) {
                        // Defaults are fine when the socket refuses them.
                    }
                    Client client = new Client(channel);
                    clients.add(client);
                    new Thread(client, "stream-client").start();
                    Log.i(TAG, "a consumer attached (" + clients.size() + " now)");
                    status("streaming");
                } catch (IOException closed) {
                    return;
                }
            }
        }, "stream-accept").start();
        return true;
    }

    /** One composited image: dropped if nothing changed, else numbered and fanned out. */
    private void onImage(Image image) {
        try {
            looked++;
            // The state file is what a phone without a UI is watched through, so
            // it follows the clock rather than the screen: a still display must
            // not make a running capture look stopped, nor a recording silent.
            if (SystemClock.elapsedRealtime() - lastStatus > 2000) {
                status(live ? "streaming" : "stopped");
            }
            Image.Plane plane = image.getPlanes()[0];
            int stride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            ByteBuffer buffer = plane.getBuffer();
            long signature = signature(buffer, stride, pixelStride);
            if (signature == previousSignature) {
                return;
            }
            previousSignature = signature;
            changed++;
            // Numbered whether or not anyone is attached: a frame number is the
            // count of changes since the capture began, not since it was watched.
            long number = ++frameNumber;
            if (clients.isEmpty() && taps.isEmpty() && recorder == null
                    && streamEncoder == null) {
                // Nobody is watching: the screen still changes, but copying 18 MB
                // of pixels into a pool nobody will read is not worth it.
                return;
            }
            byte[] pixels = take(stride * height);
            if (pixels == null) {
                skipped++;
                return;
            }
            buffer.rewind();
            buffer.get(pixels, 0, Math.min(pixels.length, buffer.remaining()));
            fanOut(new Frame(pixels, width, height, stride, number, image.getTimestamp()));
        } catch (RuntimeException failed) {
            Log.w(TAG, "could not read a frame", failed);
        } finally {
            image.close();
        }
    }

    /**
     * A change detector, not a checksum: samples every eighth pixel in both
     * directions. A caret, a character or a recoloured cell covers many samples,
     * so a change that matters is seen; nothing here needs to survive an
     * adversary.
     */
    private static long signature(ByteBuffer buffer, int stride, int pixelStride) {
        long hash = 0xcbf29ce484222325L;
        int limit = buffer.limit();
        int step = CHANGE_STEP * pixelStride;
        for (int row = 0; row < limit; row += CHANGE_STEP * stride) {
            int end = Math.min(limit, row + stride);
            for (int index = row; index + 4 <= end; index += step) {
                hash ^= buffer.getInt(index);
                hash *= 0x100000001b3L;
            }
        }
        return hash;
    }

    private void fanOut(Frame frame) {
        for (Client client : clients) {
            if (client.offer(frame)) {
                sent++;
            }
        }
        // The recording is a consumer like any other: it counts a holder and lets
        // go when the encoder has copied the frame, so it can never keep the pool
        // waiting for a slow encoder.
        Recorder writing = recorder;
        if (writing != null && writing.closed()) {
            // The encoder gave up on its own: stop saying it is recording, so a UI
            // or a status reader is not told about a file that stopped growing.
            endRecording();
            writing = null;
        }
        if (writing != null) {
            frame.holders.incrementAndGet();
            if (!writing.offer(frame)) {
                release(frame);
            }
        }
        Recorder live = streamEncoder;
        if (live != null && live != writing) {
            frame.holders.incrementAndGet();
            if (!live.offer(frame)) {
                release(frame);
            }
        }
        for (Tap tap : taps) {
            frame.holders.incrementAndGet();
            try {
                tap.onFrame(frame);
            } catch (RuntimeException broke) {
                // A tap that cannot take a frame is not the stream's problem.
                Log.w(TAG, "a tap dropped a frame", broke);
                release(frame);
            }
        }
        if (frame.holders.get() == 0) {
            giveBack(frame);
        }
    }

    private static synchronized byte[] take(int size) {
        if (bufferSize != size) {
            bufferSize = size;
            pool.clear();
            allocated = 0;
        }
        byte[] buffer = pool.poll();
        if (buffer == null && allocated < POOL) {
            allocated++;
            buffer = new byte[size];
        }
        // No buffer left means every one is still being written to a consumer:
        // the frame is dropped rather than the stream waiting for a slow link.
        return buffer;
    }

    private static synchronized void giveBack(Frame frame) {
        if (frame.released) {
            return;
        }
        frame.released = true;
        if (pool.size() < POOL) {
            pool.addLast(frame.pixels);
        }
    }

    /** One attached consumer: newest frame wins, and a stalled one is cut off. */
    private final class Client implements Runnable {
        private final SocketChannel channel;
        private final ArrayBlockingQueue<Frame> queue = new ArrayBlockingQueue<>(1);
        private volatile boolean alive = true;

        Client(SocketChannel channel) throws IOException {
            this.channel = channel;
            channel.configureBlocking(false);
        }

        boolean offer(Frame frame) {
            if (!alive) {
                return false;
            }
            Frame stale = queue.poll();
            if (stale != null) {
                release(stale);
            }
            frame.holders.incrementAndGet();
            if (!queue.offer(frame)) {
                release(frame);
                return false;
            }
            return true;
        }

        @Override
        public void run() {
            Selector selector = null;
            try {
                selector = Selector.open();
                ByteBuffer discard = ByteBuffer.allocate(1);
                while (alive) {
                    Frame frame = queue.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                    if (frame == null) {
                        // A consumer that closed while the screen was still sends
                        // nothing, so its thread has to look for the close itself.
                        if (channel.read(discard) < 0) {
                            break;
                        }
                        continue;
                    }
                    boolean whole = write(selector, frame.header());
                    whole &= write(selector, ByteBuffer.wrap(frame.pixels, 0,
                            frame.stride * frame.height));
                    release(frame);
                    if (!whole) {
                        break;
                    }
                }
            } catch (InterruptedException | IOException ended) {
                // A consumer that disconnects is normal; a broken one is the same.
                Log.i(TAG, "client ended: " + ended);
            } catch (RuntimeException broke) {
                Log.w(TAG, "client broke", broke);
            } finally {
                if (selector != null) {
                    try {
                        selector.close();
                    } catch (IOException ignored) {
                        // closing what is already gone
                    }
                }
                close();
            }
        }

        /** Writes the buffer out, or gives up once the frame's deadline has passed. */
        private boolean write(Selector selector, ByteBuffer buffer) throws IOException {
            long deadline = SystemClock.elapsedRealtime() + WRITE_DEADLINE_MS;
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) == 0) {
                    if (SystemClock.elapsedRealtime() > deadline) {
                        Log.w(TAG, "a consumer could not keep up; disconnecting it");
                        return false;
                    }
                    channel.register(selector, SelectionKey.OP_WRITE);
                    selector.select(100);
                    selector.selectedKeys().clear();
                }
            }
            return true;
        }

        private void close() {
            alive = false;
            clients.remove(this);
            Frame pending = queue.poll();
            if (pending != null) {
                release(pending);
            }
            try {
                channel.close();
            } catch (IOException ignored) {
                // already closed
            }
            status("streaming");
        }
    }

    private static void release(Frame frame) {
        if (frame.holders.decrementAndGet() <= 0) {
            giveBack(frame);
        }
    }

    /**
     * Something in this process that wants every changed frame without a socket.
     *
     * <p>A tap is the reason this exists: a pixel check or an assertion can take
     * its frames straight from the compositor's output instead of a screenshot
     * every 333 ms, so it sees every changed frame at the rate the screen changes.
     * A tap runs on the capture thread and is handed a frame it must
     * {@link Frame#release()} - the buffer goes back to the pool when the last
     * holder lets go.
     */
    public interface Tap {
        void onFrame(Frame frame);
    }

    /**
     * Asks for every changed frame from now on; false when nothing is capturing.
     *
     * @return whether the tap was attached, so a caller can fall back to screenshots
     */
    public static boolean subscribe(Tap tap) {
        if (!live) {
            return false;
        }
        taps.add(tap);
        return true;
    }

    public static void unsubscribe(Tap tap) {
        taps.remove(tap);
    }

    /** True while a capture is running, so a consumer knows where frames will come from. */
    public static boolean live() {
        return live;
    }

    /**
     * How many frames this capture has changed so far.
     *
     * <p>A screen that is not changing is the last of those frames, still on the
     * display, so something that samples the screen itself can label what it saw
     * with the number of the frame it is looking at.
     */
    public static long frameNumber() {
        return frameNumber;
    }

    /** One changed frame, shared by every consumer it was offered to. */
    public static final class Frame {
        public final byte[] pixels;
        public final int width, height, stride;
        public final long number, timestampNs;
        final AtomicInteger holders = new AtomicInteger();
        volatile boolean released;

        Frame(byte[] pixels, int width, int height, int stride, long number, long timestampNs) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
            this.stride = stride;
            this.number = number;
            this.timestampNs = timestampNs;
        }

        /** Hands this frame's buffer back once every holder is done with it. */
        public void release() {
            ScreenStream.release(this);
        }

        ByteBuffer header() {
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
            header.put((byte) 'R').put((byte) 'A').put((byte) 'W').put((byte) '1');
            header.putInt(width).putInt(height).putInt(stride).putInt(1);
            header.putLong(number).putLong(timestampNs);
            header.flip();
            return header;
        }
    }

    private void status(String state) {
        lastStatus = SystemClock.elapsedRealtime();
        publish(statusBody(state, null).toString());
    }

    /** Writes the report and keeps it for a provider answer after the service ends. */
    private void publish(String body) {
        lastReport = body;
        Status.replace(this, STATUS_FILE, body);
    }

    /** The state file's body: one key=value per line, the vocabulary readers know. */
    private StringBuilder statusBody(String state, String why) {
        StringBuilder out = new StringBuilder();
        out.append("state=").append(state).append('\n');
        if (why != null) {
            out.append("why=").append(oneLine(why)).append('\n');
        }
        out.append("port=").append(port).append('\n');
        out.append("size=").append(width).append('x').append(height).append('\n');
        out.append("scale=").append(scale).append('\n');
        out.append("format=rgba8888\n");
        out.append("frames_looked=").append(looked).append('\n');
        out.append("frames_changed=").append(changed).append('\n');
        out.append("frames_sent=").append(sent).append('\n');
        out.append("frames_skipped=").append(skipped).append('\n');
        out.append("clients=").append(clients.size()).append('\n');
        out.append("taps=").append(taps.size()).append('\n');
        Recorder running = recorder;
        out.append("recording=")
                .append(running == null ? "-" : running.file().getAbsolutePath()).append('\n');
        out.append("recorded_frames=")
                .append(running == null ? lastRecordedFrames : running.frames()).append('\n');
        out.append("recorded_ms=")
                .append(running == null ? lastRecordedMs : running.milliseconds()).append('\n');
        // What the file's zero is on the stream's clock: a frame's ts_ns minus this
        // is where its picture is in the recording. The stream numbers its frames
        // from the start of the capture, the file from its own first frame - and
        // drops the ones the encoder was too busy for - so the numbers differ.
        out.append("recording_from_ns=")
                .append(running == null ? lastRecordedFromNs
                        : Math.max(0, running.startedAtUs()) * 1000).append('\n');
        // The encoded stream: the same frames as H.264 on a second port, for a
        // viewer that would rather not receive raw RGBA. The encoder is named so a
        // reader can tell the hardware component from a software one.
        EncodedStream viewers = encoded;
        Recorder live = streamEncoder;
        out.append("encoded_port=").append(viewers == null ? "-" : String.valueOf(viewers.port()))
                .append('\n');
        out.append("encoded_clients=").append(viewers == null ? 0 : viewers.clients()).append('\n');
        out.append("encoded_frames=").append(viewers == null ? 0 : viewers.samples()).append('\n');
        out.append("encoded_bytes=").append(viewers == null ? 0 : viewers.bytes()).append('\n');
        out.append("encoder=")
                .append(running != null ? running.codecName()
                        : live != null ? live.codecName() : "-").append('\n');
        return out;
    }

    private void fail(String why) {
        live = false;
        lastFailure = why;
        publish(statusBody("failed", why).toString());
        Log.w(TAG, why);
        stopSelf();
    }

    static Runnable dialogClicker() {
        return dialogClicker;
    }

    private Notification notification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(new NotificationChannel("screen_stream",
                    "Screen stream", NotificationManager.IMPORTANCE_LOW));
        }
        return new Notification.Builder(this, "screen_stream")
                .setContentTitle("Screen stream")
                .setContentText("Raw frames on 127.0.0.1:" + port + (encodedPort < 0
                        ? ", no video port" : ", video on " + encodedPort))
                .setSmallIcon(android.R.drawable.presence_video_online)
                .build();
    }

    @Override
    public void onDestroy() {
        live = false;
        self = null;
        taps.clear();
        // Blocking, unlike the UI's stop: a service being torn down has one chance
        // to write the MP4's index, and a file without one cannot be played.
        Recorder ending = detachRecording();
        if (ending != null) {
            finishRecording(ending);
        }
        EncodedStream viewers = encoded;
        encoded = null;
        if (viewers != null) {
            viewers.close();
        }
        Recorder live = streamEncoder;
        streamEncoder = null;
        if (live != null) {
            live.stop();
        }
        if (reader != null) {
            reader.close();
        }
        if (display != null) {
            display.release();
        }
        if (projection != null) {
            projection.stop();
        }
        if (thread != null) {
            thread.quitSafely();
        }
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // closing what is already gone
            }
        }
        for (Client client : clients) {
            client.close();
        }
        status("stopped");
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
