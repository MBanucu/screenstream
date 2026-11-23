package io.github.mbanucu.screenstream;

import android.media.MediaFormat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Fragmented MP4: one init segment, then a fragment per group of pictures.
 *
 * <p>A plain MP4 cannot be read until its index is written, which only happens when
 * the file is finished - so a recording is an archive and never a live view. A
 * fragmented MP4 puts the index in the init segment and one {@code moof} in front of
 * each fragment's samples, so a reader can start at any fragment boundary and play
 * what follows. That is what makes this stream watchable while the screen is still
 * being captured.
 *
 * <p>Everything here is written by hand because {@code MediaMuxer} writes flat MP4
 * and offers no fragmented output. Only the boxes a reader needs are written: one
 * video track, length-prefixed H.264 ({@code avc1} + {@code avcC}), no edit lists, no
 * sample groups, no timing metadata track - the container's own times are exact here,
 * because the encoder is fed the frames' timestamps and its output carries them.
 *
 * <p>Times are in the track's 90 kHz timescale, which is what MP4 uses for video and
 * what keeps a variable frame rate honest: a frame's duration is the next frame's
 * presentation time minus its own, so a gap in the screen is a gap in the file
 * rather than a frame repeated to fill it.
 */
final class Fmp4 {

    /** MP4 video timescale. Durations are ticks of 1/90000 s, about 11 us. */
    static final int TIMESCALE = 90_000;

    private static final int[] MATRIX = {
        0x00010000, 0, 0,
        0, 0x00010000, 0,
        0, 0, 0x40000000,
    };

    private Fmp4() {
    }

    /**
     * The init segment: {@code ftyp} and a {@code moov} with no samples in it, only
     * the track's description and the {@code mvex} that says samples arrive later.
     */
    static byte[] init(MediaFormat format, int width, int height) {
        ByteBuffer csd0 = format.getByteBuffer("csd-0");
        ByteBuffer csd1 = format.getByteBuffer("csd-1");
        if (csd0 == null) {
            throw new IllegalArgumentException("the encoder gave no csd-0, so no avcC");
        }
        byte[] avc = avcC(csd0, csd1);

        Buf avc1 = new Buf();
        avc1.zeros(6).u16(1);
        avc1.zeros(2).zeros(2).zeros(12);
        avc1.u16(width).u16(height);
        avc1.u32(0x00480000).u32(0x00480000);
        avc1.u32(0).u16(1);
        avc1.zeros(32);
        avc1.u16(0x18).u16(0xFFFF);
        avc1.bytes(new Buf().bytes(avc).box("avcC"));

        byte[] stsd = full(0, 0).u32(1).bytes(avc1.box("avc1")).box("stsd");
        byte[] stbl = new Buf()
                .bytes(stsd)
                .bytes(full(0, 0).u32(0).box("stts"))
                .bytes(full(0, 0).u32(0).box("stsc"))
                .bytes(full(0, 0).u32(0).u32(0).box("stsz"))
                .bytes(full(0, 0).u32(0).box("stco"))
                .box("stbl");

        byte[] url = full(0, 1).box("url ");
        byte[] dref = full(0, 0).u32(1).bytes(url).box("dref");
        byte[] dinf = new Buf().bytes(dref).box("dinf");
        byte[] vmhd = full(0, 1).u16(0).u16(0).u16(0).u16(0).box("vmhd");
        byte[] minf = new Buf()
                .bytes(vmhd)
                .bytes(dinf)
                .bytes(stbl)
                .box("minf");

        byte[] mdhd = full(0, 0).u32(0).u32(0).u32(TIMESCALE).u32(0)
                .u16(0x55C4).u16(0).box("mdhd");
        byte[] hdlr = full(0, 0).u32(0).ascii("vide").zeros(12)
                .ascii("VideoHandler").u8(0).box("hdlr");
        byte[] mdia = new Buf()
                .bytes(mdhd)
                .bytes(hdlr)
                .bytes(minf)
                .box("mdia");

        byte[] tkhd = full(0, 3).u32(0).u32(0).u32(1).u32(0).u32(0)
                .u32(0).u32(0)
                .u16(0).u16(0).u16(0).u16(0)
                .matrix()
                .u32((long) width << 16).u32((long) height << 16)
                .box("tkhd");

        byte[] mvhd = full(0, 0).u32(0).u32(0).u32(1000).u32(0)
                .u32(0x00010000).u16(0x0100).u16(0).u32(0).u32(0)
                .matrix().zeros(24).u32(2).box("mvhd");

        byte[] trex = full(0, 0).u32(1).u32(1).u32(0).u32(0).u32(0).box("trex");
        byte[] mvex = new Buf().bytes(trex).box("mvex");

        byte[] trak = new Buf().bytes(tkhd).bytes(mdia).box("trak");
        byte[] moov = new Buf().bytes(mvhd).bytes(trak).bytes(mvex).box("moov");

        byte[] ftyp = new Buf().ascii("isom").u32(0x200)
                .ascii("isom").ascii("iso5").ascii("avc1").ascii("mp41")
                .box("ftyp");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ftyp);
        out.writeBytes(moov);
        return out.toByteArray();
    }

    /** One fragment: the {@code moof} to write before its samples, and its length. */
    static Frag fragment(long sequence, long baseTicks, List<Sample> samples) {
        int count = samples.size();
        int[] sizes = new int[count];
        boolean[] keys = new boolean[count];
        long[] durations = new long[count];
        long ticks = 0;
        for (int i = 0; i < count; i++) {
            Sample sample = samples.get(i);
            sizes[i] = sample.data.length;
            keys[i] = sample.key;
            // The last sample's next frame is in the next fragment, so its duration
            // is a stub: borrowing the previous delta would run past the next
            // fragment's own start time and make the track go backwards.
            long delta = i + 1 < count
                    ? samples.get(i + 1).ptsUs - sample.ptsUs
                    : 1_000;
            durations[i] = Math.max(1, delta * TIMESCALE / 1_000_000);
            ticks += durations[i];
        }
        // The data offset is relative to the start of the moof and has to point past
        // the mdat header, so build once to learn the size and again with the answer.
        byte[] first = moof(sequence, baseTicks, 0, sizes, durations, keys);
        return new Frag(moof(sequence, baseTicks, first.length + 8, sizes, durations, keys),
                ticks);
    }

    /** What a viewer needs to write a fragment: the index first, then the samples. */
    static final class Frag {
        final byte[] moof;
        /** Ticks the fragment covers, so the next one's time continues from it. */
        final long ticks;

        Frag(byte[] moof, long ticks) {
            this.moof = moof;
            this.ticks = ticks;
        }
    }

    private static byte[] moof(long sequence, long baseTicks, int dataOffset,
                               int[] sizes, long[] durations, boolean[] keys) {
        // 0x701: data offset, sample duration, sample size and sample flags follow.
        Buf trun = full(0, 0x000701).u32(sizes.length).i32(dataOffset);
        for (int i = 0; i < sizes.length; i++) {
            // 0x02000000 says "does not depend on another sample" (a keyframe);
            // anything else is a non-sync sample that needs the ones before it.
            trun.u32(durations[i]).u32(sizes[i]).u32(keys[i] ? 0x02000000L : 0x01010000L);
        }
        byte[] mfhd = full(0, 0).u32(sequence).box("mfhd");
        byte[] tfhd = full(0, 0x020000).u32(1).box("tfhd");
        byte[] tfdt = full(1, 0).u64(baseTicks).box("tfdt");
        byte[] traf = new Buf().bytes(tfhd).bytes(tfdt).bytes(trun.box("trun")).box("traf");
        return new Buf().bytes(mfhd).bytes(traf).box("moof");
    }

    /**
     * An {@code avcC} from whatever the encoder called csd-0: Android encoders
     * disagree on whether it is an Annex-B SPS or a ready-made configuration record,
     * so both are accepted.
     */
    private static byte[] avcC(ByteBuffer csd0, ByteBuffer csd1) {
        byte[] first = copy(csd0);
        if (first.length > 6 && first[0] == 1) {
            return first;
        }
        List<byte[]> sps = nals(first);
        List<byte[]> pps = csd1 == null ? List.of() : nals(copy(csd1));
        if (sps.isEmpty()) {
            throw new IllegalArgumentException("csd-0 has no SPS");
        }
        byte[] head = sps.get(0);
        Buf out = new Buf()
                .u8(1)
                .u8(head.length > 1 ? head[1] : 0x42)
                .u8(head.length > 2 ? head[2] : 0)
                .u8(head.length > 3 ? head[3] : 0x1F)
                .u8(0xFF)
                .u8(0xE0 | sps.size());
        for (byte[] nal : sps) {
            out.u16(nal.length).bytes(nal);
        }
        out.u8(pps.size());
        for (byte[] nal : pps) {
            out.u16(nal.length).bytes(nal);
        }
        return out.toBytes();
    }

    /** The NAL units in an Annex-B buffer, start codes removed. */
    private static List<byte[]> nals(byte[] data) {
        List<byte[]> found = new ArrayList<>();
        int start = -1;
        for (int i = 0; i + 3 <= data.length; i++) {
            boolean four = i + 4 <= data.length && data[i] == 0 && data[i + 1] == 0
                    && data[i + 2] == 0 && data[i + 3] == 1;
            boolean three = data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1;
            if (!four && !three) {
                continue;
            }
            if (start >= 0) {
                found.add(slice(data, start, i));
            }
            i += four ? 3 : 2;
            start = i + 1;
        }
        if (start >= 0 && start < data.length) {
            found.add(slice(data, start, data.length));
        }
        return found;
    }

    private static byte[] slice(byte[] data, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(data, from, out, 0, out.length);
        return out;
    }

    private static byte[] copy(ByteBuffer buffer) {
        ByteBuffer source = buffer.duplicate();
        source.rewind();
        byte[] out = new byte[source.remaining()];
        source.get(out);
        return out;
    }

    /**
     * The same access unit with its NAL units length-prefixed instead of separated by
     * start codes: what {@code avc1} + {@code avcC} promise a decoder, and what the
     * hardware encoder here does not produce.
     *
     * <p>Annex-B is what an encoder hands out; MP4 stores lengths, because a reader
     * has to skip a sample without scanning it. A buffer that is already
     * length-prefixed is returned untouched, so either kind of encoder works.
     */
    static byte[] lengthPrefixed(byte[] data) {
        List<int[]> nals = new ArrayList<>();
        int start = -1;
        int at = 0;
        while (at + 3 <= data.length) {
            boolean four = at + 4 <= data.length && data[at] == 0 && data[at + 1] == 0
                    && data[at + 2] == 0 && data[at + 3] == 1;
            boolean three = data[at] == 0 && data[at + 1] == 0 && data[at + 2] == 1;
            if (!four && !three) {
                at++;
                continue;
            }
            if (start >= 0) {
                nals.add(new int[]{start, at});
            }
            at += four ? 4 : 3;
            start = at;
        }
        if (start < 0) {
            // No start code: it is already length-prefixed, or empty.
            return data;
        }
        if (start < data.length) {
            nals.add(new int[]{start, data.length});
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        for (int[] nal : nals) {
            int size = nal[1] - nal[0];
            if (size <= 0) {
                continue;
            }
            out.write(size >>> 24);
            out.write(size >>> 16);
            out.write(size >>> 8);
            out.write(size);
            out.write(data, nal[0], size);
        }
        return out.toByteArray();
    }

    /** One encoded access unit: the bytes, when the stream showed them, and a flag. */
    static final class Sample {
        final byte[] data;
        final long ptsUs;
        final boolean key;

        Sample(byte[] data, long ptsUs, boolean key) {
            this.data = data;
            this.ptsUs = ptsUs;
            this.key = key;
        }
    }

    private static Buf full(int version, int flags) {
        return new Buf().u8(version).u8(flags >> 16).u8(flags >> 8).u8(flags);
    }

    /** A big-endian byte builder that knows how to wrap its contents in a box. */
    private static final class Buf {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        Buf u8(int value) {
            bytes.write(value & 0xff);
            return this;
        }

        Buf u16(int value) {
            return u8(value >> 8).u8(value);
        }

        Buf u32(long value) {
            return u16((int) (value >> 16)).u16((int) value);
        }

        Buf i32(int value) {
            return u32(value);
        }

        Buf u64(long value) {
            return u32(value >>> 32).u32(value);
        }

        Buf zeros(int count) {
            for (int i = 0; i < count; i++) {
                bytes.write(0);
            }
            return this;
        }

        Buf ascii(String text) {
            bytes.writeBytes(text.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return this;
        }

        Buf bytes(byte[] data) {
            bytes.writeBytes(data);
            return this;
        }

        Buf matrix() {
            for (int value : MATRIX) {
                u32(value);
            }
            return this;
        }

        byte[] toBytes() {
            return bytes.toByteArray();
        }

        /** Wraps what has been built so far as {@code size, type, payload}. */
        byte[] box(String type) {
            byte[] payload = bytes.toByteArray();
            Buf out = new Buf().u32(payload.length + 8L).ascii(type).bytes(payload);
            return out.toBytes();
        }
    }
}
