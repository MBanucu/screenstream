"""The raw frame protocol: a versioned header, then RGBA_8888 pixels.

The protocol is deliberately small enough to re-implement in another language:

    magic "RAW2"   4 bytes
    major          u16   protocol major version, 2 today
    minor          u16   protocol minor version
    width          u32
    height         u32
    row stride     u32   bytes per row in the payload, padding included
    format         u32   1 = RGBA_8888, 4 bytes per pixel
    frame number   u64   changed frames since the capture began
    timestamp      u64   nanoseconds, the device's elapsed clock

A client reads the magic first, then the version, and refuses a major version it
does not know before it reads the rest of the header. The sentence is the whole
point: an unknown stream is a compatibility problem, not a struct error.

Magic ``RAW1`` is protocol 1, the 36-byte header older apps served. It is still
accepted, with no version fields, so a client keeps working against an app that
was installed before the version existed. New streams are always ``RAW2``.
"""

import os
import socket
import struct
import subprocess
import time
from dataclasses import dataclass
from typing import Iterator, Optional, Tuple

MAGIC = b"RAW2"
LEGACY_MAGIC = b"RAW1"

PROTOCOL_MAJOR = 2
PROTOCOL_MINOR = 0
PROTOCOL_VERSION = "%d.%d" % (PROTOCOL_MAJOR, PROTOCOL_MINOR)

# magic, major, minor, width, height, stride, format, number, ts_ns
HEADER = struct.Struct(">4sHHIIIIQQ")
# Protocol 1: magic, width, height, stride, format, number, ts_ns
LEGACY_HEADER = struct.Struct(">4sIIIIQQ")

MAX_FRAME_BYTES = 64 * 1024 * 1024
RGBA_8888 = 1
BYTES_PER_PIXEL = 4


class ScreenstreamError(Exception):
    """The stream could not be read, or was not a screenstream stream."""


class ProtocolError(ScreenstreamError):
    """The stream speaks a version or a shape this client does not understand."""


class ConnectionClosed(ScreenstreamError):
    """The source closed, or a frame ended before its payload did."""


class SaveError(ScreenstreamError):
    """A frame could not be written as a picture."""


@dataclass(frozen=True)
class Frame:
    """One changed frame.

    The first four fields are the portable tuple: ``number, timestamp_ns, size,
    pixels``. The rest describes the payload, which is needed to write it out.
    ``pixels`` includes row padding; use :attr:`body` for width * height * 4.
    """

    number: int
    timestamp_ns: int
    size: int
    pixels: bytes
    width: int
    height: int
    stride: int
    pixel_format: int
    protocol_version: Tuple[int, int]

    def __iter__(self):
        return iter((self.number, self.timestamp_ns, self.size, self.pixels))

    @property
    def body(self) -> bytes:
        """The pixels with the row padding removed: what a picture wants."""
        row = self.width * BYTES_PER_PIXEL
        if self.stride == row:
            return bytes(self.pixels)
        return b"".join(
            bytes(self.pixels[y * self.stride:y * self.stride + row])
            for y in range(self.height)
        )


class _ReadTimeout(Exception):
    """A socket read passed its deadline; ``got`` bytes of the part arrived."""

    def __init__(self, got: int):
        super().__init__(got)
        self.got = got


def _header_progress(got: int, total: int) -> str:
    return ("%d of %d bytes of a frame header" % (got, total)) if got else "a frame header"


class Connection:
    """One attached consumer of the raw stream.

    Use :func:`connect` to get one. A socket-like object can be passed directly
    (that is what the offline tests do), as long as it has ``recv_into`` and
    ``settimeout``.
    """

    def __init__(self, sock):
        self.sock = sock
        # Set once the first frame's header has been read.
        self.protocol_version: Optional[Tuple[int, int]] = None

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass

    def __enter__(self) -> "Connection":
        return self

    def __exit__(self, *exc_info) -> None:
        self.close()

    def _set_timeout(self, timeout: Optional[float]) -> None:
        try:
            self.sock.settimeout(timeout)
        except (AttributeError, OSError):
            # A test double, or a socket that refuses it: the caller's deadline
            # still applies to whole frames, not to each read.
            pass

    def _read_exactly(self, size: int, what: str) -> bytes:
        data = bytearray(size)
        view = memoryview(data)
        got = 0
        while got < size:
            try:
                chunk = self.sock.recv_into(view[got:], size - got)
            except socket.timeout:
                raise _ReadTimeout(got)
            if chunk <= 0:
                raise ConnectionClosed(
                    "screenstream: the source closed after %d of %d bytes of %s."
                    % (got, size, what))
            got += chunk
        return bytes(data)

    def _read_header_part(self, size: int, total: int) -> bytes:
        try:
            return self._read_exactly(size, "a frame header")
        except _ReadTimeout as stalled:
            raise ProtocolError(
                "screenstream: the stream stopped in the middle of a frame header "
                "(%s)." % _header_progress(stalled.got, total))

    def read_frame(self, timeout: Optional[float] = None) -> Optional[Frame]:
        """Reads one frame, or returns None if no header arrived before ``timeout``.

        A timeout is only a quiet screen when it happens before the first byte of
        a header. A partial header or payload is a malformed stream, and raises
        :class:`ProtocolError` with a sentence rather than a decode error.
        """
        self._set_timeout(timeout)
        try:
            magic = self._read_exactly(4, "a frame header")
        except _ReadTimeout as stalled:
            if stalled.got == 0:
                return None
            raise ProtocolError(
                "screenstream: the stream stopped in the middle of a frame header "
                "(%s)." % _header_progress(stalled.got, HEADER.size))

        if magic == LEGACY_MAGIC:
            major, minor = 1, 0
            rest = self._read_header_part(LEGACY_HEADER.size - 4, LEGACY_HEADER.size)
            width, height, stride, pixel_format, number, ts_ns = struct.unpack(
                ">IIIIQQ", rest)
        elif magic == MAGIC:
            version = self._read_header_part(4, HEADER.size)
            major, minor = struct.unpack(">HH", version)
            if major != PROTOCOL_MAJOR:
                raise ProtocolError(
                    "screenstream: the stream speaks protocol %d.%d, and this "
                    "client understands %d.x; upgrade the client or the app."
                    % (major, minor, PROTOCOL_MAJOR))
            rest = self._read_header_part(HEADER.size - 8, HEADER.size)
            width, height, stride, pixel_format, number, ts_ns = struct.unpack(
                ">IIIIQQ", rest)
        else:
            raise ProtocolError(
                "screenstream: not a frame header (magic %r); expected %r or %r."
                % (magic, MAGIC, LEGACY_MAGIC))

        self.protocol_version = (major, minor)
        if width <= 0 or height <= 0 or stride < width * BYTES_PER_PIXEL:
            raise ProtocolError(
                "screenstream: impossible frame geometry %dx%d with stride %d."
                % (width, height, stride))
        if pixel_format != RGBA_8888:
            raise ProtocolError(
                "screenstream: unknown pixel format %d; this client reads "
                "RGBA_8888 (format 1)." % pixel_format)
        size = stride * height
        if size <= 0 or size > MAX_FRAME_BYTES:
            raise ProtocolError(
                "screenstream: refusing a %d-byte frame (%dx%d, stride %d)."
                % (size, width, height, stride))

        try:
            pixels = self._read_exactly(size, "a frame")
        except _ReadTimeout as stalled:
            raise ProtocolError(
                "screenstream: the stream stopped in the middle of a frame "
                "(%d of %d pixel bytes)." % (stalled.got, size))
        return Frame(number, ts_ns, size, pixels, width, height, stride,
                     pixel_format, (major, minor))

    def frames(self, timeout: Optional[float] = None) -> Iterator[Frame]:
        """Every frame until the source closes; a quiet timeout just keeps waiting."""
        while True:
            try:
                frame = self.read_frame(timeout)
            except ConnectionClosed:
                return
            if frame is not None:
                yield frame

    def wait_for_change(self, timeout: float,
                        since: Optional[int] = None) -> Optional[Frame]:
        """Waits up to ``timeout`` for a frame newer than ``since``.

        ``since`` is a frame number, normally the last one seen. A still screen
        sends nothing, so this returns None when the timeout passes. A closed
        connection raises :class:`ConnectionClosed`.
        """
        deadline = time.monotonic() + max(0.0, timeout)
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return None
            frame = self.read_frame(remaining)
            if frame is None:
                return None
            if since is None or frame.number > since:
                return frame


def connect(host, port, timeout: float = 10.0) -> Connection:
    """Connects to a stream, retrying while the capture is still starting.

    ``timeout`` is how long to keep trying, not a socket timeout: a connection
    that succeeds has no read timeout, so :meth:`Connection.wait_for_change`
    decides how long a still screen may stay quiet.
    """
    deadline = time.monotonic() + max(0.0, timeout)
    last_error = None
    while True:
        remaining = deadline - time.monotonic()
        try:
            sock = socket.create_connection(
                (host, int(port)), timeout=max(0.1, min(5.0, remaining)))
        except OSError as failed:
            last_error = failed
            if time.monotonic() >= deadline:
                raise ConnectionError(
                    "screenstream: cannot reach %s:%d (%s)."
                    % (host, int(port), last_error))
            time.sleep(0.2)
            continue
        sock.settimeout(None)
        return Connection(sock)


def save_png(frame: Frame, path) -> str:
    """Writes one frame as a PNG through ffmpeg.

    No image library is a dependency; ffmpeg is expected where pictures are
    wanted, exactly as the command-line clients already expected it. Raises
    :class:`SaveError` with a sentence when it is missing or refuses the frame.
    """
    path = os.fspath(path)
    directory = os.path.dirname(os.path.abspath(path))
    os.makedirs(directory, exist_ok=True)
    argv = [
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-f", "rawvideo", "-pixel_format", "rgba",
        "-video_size", "%dx%d" % (frame.width, frame.height),
        "-i", "pipe:0", "-frames:v", "1", path,
    ]
    try:
        result = subprocess.run(argv, input=frame.body,
                                capture_output=True, text=True)
    except FileNotFoundError:
        raise SaveError(
            "screenstream: ffmpeg is not installed, so %s was not written." % path)
    if result.returncode != 0:
        raise SaveError(
            "screenstream: ffmpeg could not write %s: %s"
            % (path, (result.stderr or "").strip()))
    return path
