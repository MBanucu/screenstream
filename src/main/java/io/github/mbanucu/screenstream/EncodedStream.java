package io.github.mbanucu.screenstream;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

/**
 * The same frames as the raw stream, H.264-encoded and sent as fragmented MP4.
 *
 * <p>The raw stream is the display itself - 18 MB a frame at 1440x3200 - which is
 * right for a machine on the other end of the cable and useless for anything else.
 * This is the same capture for a viewer that would rather not receive a gigabyte a
 * second: the frames the raw stream copies go through the same encoder the recording
 * uses, and come out as H.264 at a couple of megabits, about a hundredth of a
 * percent of the raw bytes, with the screen's own variable frame rate preserved.
 *
 * <p>One encoder feeds both consumers, so a viewer and the file cannot disagree
 * about what the screen showed. The encoder only exists while someone is watching:
 * the first viewer starts it - or, while a recording runs, attaches to its encoder -
 * and the last one leaving stops it, because an encoder running for nobody is a
 * phone warming up for nothing.
 *
 * <p>Each viewer gets its own init segment and its own fragments. A viewer that
 * attaches mid-stream cannot decode until a keyframe arrives, so attaching asks the
 * encoder for one (rather than waiting for the two-second interval) and the viewer's
 * fragments start there. A viewer too slow to keep up is disconnected rather than
 * allowed to stall the encoder: the queue is small on purpose.
 *
 * <p>The bytes are a fragmented MP4 rather than raw Annex-B NAL units because a
 * fragmented MP4 is playable by anything that can play a file - {@code ffplay},
 * VLC, a browser - with no custom client and no sample metadata of its own.
 */
final class EncodedStream implements Recorder.Sink {

    /** The port this listens on by default, next to the raw stream's 7100. */
    static final int PORT = 7101;

    private static final String TAG = "ScreenStream";
    /** Eight samples of slack: about a megabyte, and enough for a spike. */
    private static final int QUEUE = 8;
    /** A fragment per GOP, or sooner if it grows past this. */
    private static final int FRAGMENT_SAMPLES = 60;
    private static final int FRAGMENT_BYTES = 1 << 20;
    /**
     * Or sooner if it covers a second: a still screen produces almost no frames, and
     * a viewer must not wait for a keyframe that may be minutes away.
     */
    private static final long FRAGMENT_SPAN_US = 1_000_000;
    /** How long one write may take before the viewer is considered gone. */
    private static final long WRITE_DEADLINE_MS = 2000;

    private final ServerSocketChannel server;
    /** The port it actually bound: the requested one, or one the kernel chose. */
    private final int port;
    private final IntConsumer onClients;
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final AtomicLong samples = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private volatile Recorder owner;
    /** The init segment a viewer needs before any fragment makes sense. */
    private volatile byte[] init;
    /** Bumped whenever the init segment is replaced, so viewers resend it. */
    private volatile long generation;
    private volatile boolean closed;

    static EncodedStream open(IntConsumer onClients) {
        return open(PORT, onClients);
    }

    /**
     * Binds {@code requestedPort}, which may be 0 for one the kernel chooses.
     *
     * <p>A port that cannot be opened costs the raw stream nothing, so this
     * returns null rather than failing the capture; the message names the port
     * that could not be used.
     */
    static EncodedStream open(int requestedPort, IntConsumer onClients) {
        try {
            return new EncodedStream(requestedPort, onClients);
        } catch (IOException | RuntimeException failed) {
            Log.w(TAG, "no encoded stream on 127.0.0.1:" + requestedPort + ": " + failed);
            return null;
        }
    }

    private EncodedStream(int requestedPort, IntConsumer onClients) throws IOException {
        this.onClients = onClients;
        server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), requestedPort));
        port = ((InetSocketAddress) server.getLocalAddress()).getPort();
        new Thread(this::accept, "screen-stream-encoded-accept").start();
        Log.i(TAG, "encoded stream on 127.0.0.1:" + port);
    }

    /** The port it bound, which is the requested one unless that was 0. */
    int port() {
        return port;
    }

    /** Points the stream at an encoder, or at none; viewers wait for a keyframe. */
    synchronized void attach(Recorder recorder) {
        if (owner == recorder) {
            return;
        }
        if (owner != null) {
            owner.removeSink(this);
        }
        owner = recorder;
        if (recorder != null) {
            recorder.addSink(this);
            MediaFormat known = recorder.format();
            if (known != null) {
                format(known);
            }
            recorder.requestSync();
        }
        for (Client client : clients) {
            client.rebase();
        }
    }

    int clients() {
        return clients.size();
    }

    long samples() {
        return samples.get();
    }

    long bytes() {
        return bytes.get();
    }

    /** Stops listening and disconnects every viewer. */
    void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
            // closing what is already gone
        }
        for (Client client : clients) {
            client.close();
        }
    }

    @Override
    public void format(MediaFormat format) {
        try {
            int width = format.containsKey(MediaFormat.KEY_WIDTH)
                    ? format.getInteger(MediaFormat.KEY_WIDTH) : 0;
            int height = format.containsKey(MediaFormat.KEY_HEIGHT)
                    ? format.getInteger(MediaFormat.KEY_HEIGHT) : 0;
            // The init segment's udta takes the device clock's zero from the encoder
            // the viewer is watching, so the track's own times can be placed on it.
            long baseUs = owner != null ? owner.startedAtUs() : -1;
            byte[] segment = Fmp4.init(format, width, height, Math.max(0, baseUs) * 1000);
            // The same encoder announces its format more than once, and a new
            // generation would make every viewer wait for another keyframe.
            if (java.util.Arrays.equals(segment, init)) {
                return;
            }
            init = segment;
            generation++;
            Log.i(TAG, "encoded stream: init segment for " + width + "x" + height
                    + (baseUs >= 0 ? ", device base " + baseUs * 1000 + " ns"
                    : ", no device base yet"));
        } catch (RuntimeException failed) {
            Log.w(TAG, "encoded stream has no init segment: " + failed);
        }
    }

    @Override
    public void sample(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        if (clients.isEmpty() || info.size <= 0) {
            return;
        }
        byte[] data = new byte[info.size];
        ByteBuffer source = buffer.duplicate();
        source.position(info.offset);
        source.limit(info.offset + info.size);
        source.get(data);
        boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        // The encoder hands out Annex-B; an avc1 track stores lengths. Converted
        // once here, not once per viewer.
        Fmp4.Sample sample = new Fmp4.Sample(Fmp4.lengthPrefixed(data),
                info.presentationTimeUs, key);
        samples.incrementAndGet();
        for (Client client : clients) {
            client.offer(sample);
        }
    }

    private void accept() {
        while (!closed && server.isOpen()) {
            try {
                SocketChannel channel = server.accept();
                if (channel == null) {
                    continue;
                }
                try {
                    channel.socket().setTcpNoDelay(true);
                    channel.socket().setSendBufferSize(1 << 20);
                } catch (IOException ignored) {
                    // Defaults are fine when the socket refuses them.
                }
                Client client = new Client(channel);
                clients.add(client);
                new Thread(client, "screen-stream-encoded-client").start();
                changed();
                Recorder current = owner;
                if (current != null) {
                    current.requestSync();
                }
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void sync() {
        Recorder current = owner;
        if (current != null) {
            current.requestSync();
        }
    }

    private void changed() {
        try {
            onClients.accept(clients.size());
        } catch (RuntimeException failed) {
            Log.w(TAG, "encoded stream listener failed", failed);
        }
    }

    /** One viewer: its own fragment timeline, its own queue, its own thread. */
    private final class Client implements Runnable {
        private final SocketChannel channel;
        private final ArrayBlockingQueue<Fmp4.Sample> queue = new ArrayBlockingQueue<>(QUEUE);
        private volatile boolean waiting = true;
        private volatile boolean alive = true;

        Client(SocketChannel channel) {
            this.channel = channel;
        }

        void rebase() {
            waiting = true;
        }

        void offer(Fmp4.Sample sample) {
            if (!alive) {
                return;
            }
            if (!queue.offer(sample)) {
                // A viewer that cannot take a megabyte is not watching; dropping it
                // is better than letting it hold the encoder's queue.
                Log.i(TAG, "encoded stream: a viewer could not keep up");
                close();
            }
        }

        @Override
        public void run() {
            long sequence = 0;
            long endTicks = 0;
            long sentGeneration = -1;
            List<Fmp4.Sample> fragment = new ArrayList<>();
            Fmp4.Sample pending = null;
            try {
                while (alive) {
                    byte[] segment = init;
                    if (segment != null && sentGeneration != generation) {
                        // The encoder re-announced its format (a new SPS, which MTK
                        // does after a quiet spell). What this viewer already has
                        // belongs to the old one, so it goes out first.
                        if (sentGeneration >= 0 && (!fragment.isEmpty() || pending != null)) {
                            endTicks = flush(sequence++, fragment, pending, endTicks);
                            pending = null;
                        }
                        send(ByteBuffer.wrap(segment));
                        sentGeneration = generation;
                        waiting = true;
                        fragment.clear();
                        pending = null;
                        // A viewer that attaches before the encoder has produced
                        // anything gets the init when that format arrives, which is
                        // after the stream's only keyframe has already gone by. Ask
                        // for another, or this viewer waits for one that never comes.
                        sync();
                    }
                    Fmp4.Sample sample = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (sample == null) {
                        continue;
                    }
                    if (waiting) {
                        if (!sample.key) {
                            continue;
                        }
                        waiting = false;
                        Log.i(TAG, "encoded stream: a viewer starts at frame "
                                + sample.ptsUs / 1000 + " ms");
                    }
                    if (sample.key) {
                        if (!fragment.isEmpty() || pending != null) {
                            endTicks = flush(sequence++, fragment, pending, endTicks);
                        }
                        pending = sample;
                    } else {
                        if (pending != null) {
                            fragment.add(pending);
                        }
                        pending = sample;
                    }
                    long first = fragment.isEmpty()
                            ? (pending == null ? sample.ptsUs : pending.ptsUs)
                            : fragment.get(0).ptsUs;
                    long span = pending == null ? 0 : pending.ptsUs - first;
                    if (fragment.size() >= FRAGMENT_SAMPLES || bytesIn(fragment) >= FRAGMENT_BYTES
                            || span >= FRAGMENT_SPAN_US) {
                        endTicks = flush(sequence++, fragment, pending, endTicks);
                        pending = null;
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException gone) {
                Log.i(TAG, "encoded stream: a viewer left (" + gone.getMessage() + ")");
            } finally {
                close();
            }
        }

        /** Writes one fragment and returns the tick its last sample ends on. */
        private long flush(long sequence, List<Fmp4.Sample> fragment, Fmp4.Sample last,
                           long endTicks) throws IOException {
            List<Fmp4.Sample> all = new ArrayList<>(fragment);
            if (last != null) {
                all.add(last);
            }
            fragment.clear();
            if (all.isEmpty()) {
                return endTicks;
            }
            long base = all.get(0).ptsUs * Fmp4.TIMESCALE / 1_000_000;
            if (base <= endTicks) {
                // A new encoder restarted the clock; the track's time must not go
                // backwards, so this fragment continues where the last one ended.
                base = endTicks + 1;
            }
            Fmp4.Frag frag = Fmp4.fragment(sequence, base, all);
            send(ByteBuffer.wrap(frag.moof));
            int size = 8;
            for (Fmp4.Sample sample : all) {
                size += sample.data.length;
            }
            ByteBuffer header = ByteBuffer.allocate(8);
            header.putInt(size);
            header.put((byte) 'm').put((byte) 'd').put((byte) 'a').put((byte) 't');
            header.flip();
            long written = frag.moof.length + size;
            send(header);
            for (Fmp4.Sample sample : all) {
                send(ByteBuffer.wrap(sample.data));
            }
            bytes.addAndGet(written);
            return base + frag.ticks;
        }

        private int bytesIn(List<Fmp4.Sample> samples) {
            int total = 0;
            for (Fmp4.Sample sample : samples) {
                total += sample.data.length;
            }
            return total;
        }

        /** Writes it all, or gives up on a viewer that has stopped reading. */
        private void send(ByteBuffer buffer) throws IOException {
            long deadline = SystemClock.elapsedRealtime() + WRITE_DEADLINE_MS;
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) == 0) {
                    if (SystemClock.elapsedRealtime() > deadline) {
                        throw new IOException("a viewer stopped reading");
                    }
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted");
                    }
                }
            }
        }

        void close() {
            if (!alive) {
                return;
            }
            alive = false;
            Fmp4.Sample stale = queue.poll();
            while (stale != null) {
                stale = queue.poll();
            }
            try {
                channel.close();
            } catch (IOException ignored) {
                // closing what is already gone
            }
            if (clients.remove(this)) {
                changed();
            }
        }
    }
}
