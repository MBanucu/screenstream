package io.github.mbanucu.screenstream;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The display as an H.264 track in an MP4 file.
 *
 * <p>Recording has to share the one virtual display a projection allows - a second
 * {@code createVirtualDisplay} on the same token is refused and invalidates the
 * capture - so this is fed the frames the raw stream already copied, and encodes
 * them itself. That has one property worth having: the video and the socket carry
 * the same frames, so a picture in the file and a frame number in a test answer are
 * the same moment, not two captures that were close together.
 *
 * <p>The cost is the colour conversion the compositor would otherwise do: the
 * encoder wants YUV and the stream is RGBA, so this converts on its own thread and
 * drops frames the encoder cannot keep up with. A still screen therefore records as
 * a still MP4 - no frames means nothing to encode - and a busy screen records at
 * whatever rate the conversion allows.
 *
 * <p>Timestamps are the stream's own presentation times, shifted so the file starts
 * at zero.
 */
final class Recorder {

    private static final String TAG = "ScreenStream";
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int FRAME_RATE = 30;
    /** A keyframe every two seconds: what players and RTMP ingest both expect. */
    private static final int I_FRAME_SECONDS = 2;
    /** Two frames of slack between capture and conversion; older ones are dropped. */
    private static final int QUEUE = 2;
    private static final long DRAIN_MS = 5000;

    private final MediaCodec codec;
    private final MediaMuxer muxer;
    private final File file;
    private final int width, height;
    private final Thread worker;
    private final ArrayBlockingQueue<ScreenStream.Frame> queue = new ArrayBlockingQueue<>(QUEUE);

    private volatile boolean ending;
    private volatile boolean closed;
    private volatile long frames;
    private volatile long lastUs;
    private volatile String failure;
    private int track = -1;
    private boolean started;
    private long baseUs = -1;

    /** Starts encoding into {@code file}; the caller owns {@link #stop()}. */
    static Recorder start(File file, int width, int height) throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        // About 0.12 bits per pixel per frame at 30 fps: 13.8 Mbps at 1440x3200,
        // 3.4 at half size. Clamped so a small display is not starved and a big one
        // does not make a file nothing will play.
        int bitrate = Math.max(2_000_000, Math.min(20_000_000, width * height * 3));
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_SECONDS);
        MediaCodec codec = MediaCodec.createEncoderByType(MIME);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        MediaMuxer muxer = new MediaMuxer(file.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        Recorder recorder = new Recorder(codec, muxer, file, width, height);
        codec.start();
        recorder.worker.start();
        return recorder;
    }

    private Recorder(MediaCodec codec, MediaMuxer muxer, File file, int width, int height) {
        this.codec = codec;
        this.muxer = muxer;
        this.file = file;
        this.width = width;
        this.height = height;
        this.worker = new Thread(this::run, "screen-stream-record");
    }

    File file() {
        return file;
    }

    long frames() {
        return frames;
    }

    /** How long the file is so far, from the last timestamp written. */
    long milliseconds() {
        return lastUs / 1000;
    }

    /** Why it stopped early, or null. */
    String failure() {
        return failure;
    }

    /**
     * Hands one changed frame to the encoder.
     *
     * <p>Called on the capture thread, so it only queues: the conversion and the
     * encoding are someone else's work. The caller has counted a holder, so a frame
     * that does not fit is released here and never blocks the screen.
     */
    /** True once the encoder has stopped, whether it was asked to or it failed. */
    boolean closed() {
        return closed;
    }

    boolean offer(ScreenStream.Frame frame) {
        if (closed || ending) {
            return false;
        }
        ScreenStream.Frame stale = queue.poll();
        if (stale != null) {
            stale.release();
        }
        return queue.offer(frame);
    }

    /** Ends the track and finalises the file, waiting for the index to be written. */
    void stop() {
        ending = true;
        if (Thread.currentThread() != worker) {
            try {
                worker.join(DRAIN_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        close();
    }

    private void run() {
        try {
            while (true) {
                ScreenStream.Frame frame = queue.poll(200, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    try {
                        encode(frame);
                    } finally {
                        frame.release();
                    }
                    drain(0);
                } else if (ending) {
                    break;
                } else {
                    drain(0);
                }
            }
            endOfStream();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException broke) {
            failure = String.valueOf(broke);
            Log.w(TAG, "recording broke", broke);
        } finally {
            close();
        }
    }

    /** Converts one RGBA frame into the encoder's input and queues it. */
    private void encode(ScreenStream.Frame frame) {
        int index = codec.dequeueInputBuffer(10_000);
        if (index < 0) {
            // The encoder has no room: this frame is dropped rather than waited for.
            return;
        }
        ByteBuffer input = codec.getInputBuffer(index);
        Image in = codec.getInputImage(index);
        if (input == null || in == null) {
            codec.queueInputBuffer(index, 0, 0, 0, 0);
            return;
        }
        convert(frame, in);
        // The size is the input buffer's, not the sum of what the planes report:
        // the planes are views with their own padding and add up to more than the
        // buffer the encoder will accept.
        int size = input.capacity();
        long us = frame.timestampNs / 1000;
        if (baseUs < 0) {
            baseUs = us;
        }
        codec.queueInputBuffer(index, 0, size, Math.max(0, us - baseUs), 0);
    }

    /**
     * RGBA to YUV 4:2:0 in whatever layout the encoder asked for, one plane at a
     * time and by stride, because "flexible" means the layout is the device's.
     */
    private void convert(ScreenStream.Frame frame, Image in) {
        Image.Plane[] planes = in.getPlanes();
        Image.Plane yp = planes[0], up = planes[1], vp = planes[2];
        ByteBuffer yb = yp.getBuffer(), ub = up.getBuffer(), vb = vp.getBuffer();
        int yRow = yp.getRowStride(), yStep = yp.getPixelStride();
        int uRow = up.getRowStride(), uStep = up.getPixelStride();
        int vRow = vp.getRowStride(), vStep = vp.getPixelStride();
        byte[] rgba = frame.pixels;
        int stride = frame.stride;
        for (int row = 0; row < height; row++) {
            int source = row * stride;
            int yAt = row * yRow;
            boolean chromaRow = (row & 1) == 0;
            int chromaAt = (row >> 1) * uRow;
            int vAt = (row >> 1) * vRow;
            for (int col = 0; col < width; col++) {
                int at = source + col * 4;
                int r = rgba[at] & 0xff;
                int g = rgba[at + 1] & 0xff;
                int b = rgba[at + 2] & 0xff;
                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                yb.put(yAt + col * yStep, clamp(y));
                if (chromaRow && (col & 1) == 0) {
                    // One chroma sample per 2x2 block, taken from its first pixel:
                    // screen content is mostly luma, and averaging would cost twice
                    // the multiplies for a difference nothing here can see.
                    ub.put(chromaAt + (col >> 1) * uStep,
                            clamp(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128));
                    vb.put(vAt + (col >> 1) * vStep,
                            clamp(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128));
                }
            }
        }
    }

    private static byte clamp(int value) {
        return (byte) (value < 0 ? 0 : Math.min(value, 255));
    }

    /** Writes whatever the encoder has ready. */
    private void drain(int timeoutUs) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int index = codec.dequeueOutputBuffer(info, timeoutUs);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
                started = true;
                continue;
            }
            if (index < 0) {
                continue;
            }
            write(index, info);
            boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            codec.releaseOutputBuffer(index, false);
            if (end) {
                return;
            }
            timeoutUs = 0;
        }
    }

    private void write(int index, MediaCodec.BufferInfo info) {
        ByteBuffer buffer = codec.getOutputBuffer(index);
        if (buffer == null || track < 0 || info.size <= 0
                || (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            return;
        }
        muxer.writeSampleData(track, buffer, info);
        frames++;
        lastUs = info.presentationTimeUs;
    }

    /** Asks the encoder for its last frames and waits for the end-of-stream flag. */
    private void endOfStream() {
        try {
            int index = codec.dequeueInputBuffer(100_000);
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
        } catch (RuntimeException ignored) {
            // An encoder that already failed does not need telling.
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long deadline = System.currentTimeMillis() + DRAIN_MS;
        while (System.currentTimeMillis() < deadline) {
            int index = codec.dequeueOutputBuffer(info, 10_000);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
                started = true;
                continue;
            }
            if (index < 0) {
                continue;
            }
            write(index, info);
            boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            codec.releaseOutputBuffer(index, false);
            if (end) {
                return;
            }
        }
    }

    private synchronized void close() {
        // The worker closes when it ends, and stop() closes after waiting for it:
        // whichever gets here first releases, the other returns.
        if (closed) {
            return;
        }
        closed = true;
        ScreenStream.Frame pending = queue.poll();
        if (pending != null) {
            pending.release();
        }
        try {
            codec.stop();
        } catch (RuntimeException ignored) {
            // A codec that already failed does not need stopping.
        }
        codec.release();
        // The muxer writes the MP4 index in stop(); without it the file is samples
        // with no way to seek, so this is the part that makes it a video.
        if (started) {
            try {
                muxer.stop();
            } catch (RuntimeException failed) {
                failure = String.valueOf(failed);
                Log.w(TAG, "could not finish the recording", failed);
            }
        }
        muxer.release();
    }
}
