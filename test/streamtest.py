"""In-memory builders shared by the offline wire-format tests.

No socket is opened and no phone is involved: a frame, an init segment and a
fragment are bytes here, exactly as the app writes them.
"""

import socket
import struct

HEADER = struct.Struct(">4sHHIIIIQQ")
LEGACY_HEADER = struct.Struct(">4sIIIIQQ")
BASE_UUID = bytes.fromhex("6d42414e7563750073747265616d0001")


def frame_bytes(number, timestamp_ns, width=2, height=1, stride=None,
                pixels=None, major=2, minor=0, magic=b"RAW2"):
    """One protocol-2 frame, header and pixels, exactly as the app writes it."""
    if stride is None:
        stride = width * 4
    if pixels is None:
        size = stride * height
        pixels = bytes((number + i) & 0xFF for i in range(size))
    return (HEADER.pack(magic, major, minor, width, height, stride, 1,
                        number, timestamp_ns) + bytes(pixels))


def legacy_frame_bytes(number, timestamp_ns, width=2, height=1, stride=None,
                       pixels=None):
    """A protocol-1 (magic RAW1) frame, which a current client still accepts."""
    if stride is None:
        stride = width * 4
    if pixels is None:
        pixels = bytes(stride * height)
    return (LEGACY_HEADER.pack(b"RAW1", width, height, stride, 1,
                               number, timestamp_ns) + bytes(pixels))


def box(kind, payload=b""):
    """One MP4 box: size, type, payload."""
    return struct.pack(">I4s", len(payload) + 8, kind) + payload


def uuid_base_box(value):
    """The private udta child that says what the track's zero is on the clock."""
    return box(b"uuid", BASE_UUID + struct.pack(">IIQ", 1, 0, value))


def init_segment(base_ns=None, ftyp=b"isom\x00\x00\x02\x00"):
    """ftyp + a moov with the metadata box, shaped like Fmp4.init writes it."""
    moov_payload = box(b"mvhd", b"\x00" * 96)
    if base_ns is not None:
        moov_payload += box(b"udta", uuid_base_box(base_ns))
    return box(b"ftyp", ftyp) + box(b"moov", moov_payload)


def truncated_fragment():
    """A complete moof followed by an mdat whose declared size runs past the end."""
    moof = box(b"moof", box(b"mfhd", struct.pack(">II", 0, 1)))
    declared = 8 + 64
    return moof + struct.pack(">I4s", declared, b"mdat") + b"\x00" * 16


class FakeSocket:
    """A socket-like source served from a byte string, in chosen read sizes.

    ``reads`` is consumed once; afterwards a read returns what is asked for.
    With ``eof=False`` an exhausted source raises ``socket.timeout`` instead of
    closing, which is what a still screen looks like to the client.
    """

    def __init__(self, data, reads=None, eof=True):
        self.data = bytes(data)
        self.position = 0
        self.reads = list(reads or [])
        self.eof = eof
        self.timeout = None

    def settimeout(self, value):
        self.timeout = value

    def close(self):
        self.position = len(self.data)

    def recv_into(self, view, size):
        if self.position >= len(self.data):
            if not self.eof:
                raise socket.timeout()
            return 0
        limit = size
        if self.reads:
            limit = min(limit, self.reads.pop(0))
        chunk = self.data[self.position:self.position + limit]
        view[:len(chunk)] = chunk
        self.position += len(chunk)
        return len(chunk)
