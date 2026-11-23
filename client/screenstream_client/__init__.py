"""A client for screenstream: the phone's screen as frames on a local port.

The app serves one TCP port; this package reads it without Android and without
a device, so a test can build frames in memory or connect over an adb forward:

    from screenstream_client import connect

    with connect("127.0.0.1", 7100) as link:
        frame = link.wait_for_change(5)
        print(frame.number, frame.timestamp_ns, frame.size)

The command-line clients are thin wrappers over the same functions:
``screenstream-stream`` reads the raw frames and ``screenstream-fmp4`` saves
the encoded stream as a fragmented MP4.

Protocol compatibility is a major-version rule: this client speaks 2.x, and a
stream that announces another major version is refused with a sentence instead
of being read as if the fields still meant what they did. Protocol 1 (magic
``RAW1``) is still accepted for apps installed before the version existed.
"""

from .fmp4 import BASE_UUID, Box, base_ns, boxes, read_fmp4
from .wire import (
    BYTES_PER_PIXEL,
    HEADER,
    LEGACY_HEADER,
    LEGACY_MAGIC,
    MAGIC,
    MAX_FRAME_BYTES,
    PROTOCOL_MAJOR,
    PROTOCOL_MINOR,
    PROTOCOL_VERSION,
    RGBA_8888,
    Connection,
    ConnectionClosed,
    Frame,
    ProtocolError,
    SaveError,
    ScreenstreamError,
    connect,
    save_png,
)

__all__ = [
    "BASE_UUID",
    "BYTES_PER_PIXEL",
    "Box",
    "Connection",
    "ConnectionClosed",
    "Frame",
    "HEADER",
    "LEGACY_HEADER",
    "LEGACY_MAGIC",
    "MAGIC",
    "MAX_FRAME_BYTES",
    "PROTOCOL_MAJOR",
    "PROTOCOL_MINOR",
    "PROTOCOL_VERSION",
    "ProtocolError",
    "RGBA_8888",
    "SaveError",
    "ScreenstreamError",
    "base_ns",
    "boxes",
    "connect",
    "frames",
    "read_fmp4",
    "save_png",
    "wait_for_change",
]


def frames(connection, timeout=None):
    """The frames of an already-connected stream; see :meth:`Connection.frames`."""
    return connection.frames(timeout)


def wait_for_change(connection, timeout, since=None):
    """The next changed frame; see :meth:`Connection.wait_for_change`."""
    return connection.wait_for_change(timeout, since)
