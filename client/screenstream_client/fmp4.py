"""The encoded stream's container: boxes, the init segment, and ``base_ns``.

A fragmented MP4 is a sequence of length-prefixed boxes. The init segment
(``ftyp`` + ``moov``) describes the track and carries the device clock's reading
for the track's zero in a private ``udta``/``uuid`` box; every fragment after it
is a ``moof`` and an ``mdat`` that can be played as it arrives.

:func:`read_fmp4` is the offline parser: hand it bytes or a binary file and it
yields every complete top-level box. A *truncated* box at the end is not
yielded and is not an error - a live stream can stop in the middle of one - so a
caller can tell what is whole from what is not.
"""

import os
import struct
from typing import Iterator, NamedTuple, Optional

# The udta/uuid box's identifier: a private extension, skipped by size by every
# reader that does not know it.
BASE_UUID = bytes.fromhex("6d42414e7563750073747265616d0001")


class Box(NamedTuple):
    """One complete top-level box: its type, offset, total size and body."""

    kind: bytes
    offset: int
    size: int
    body: bytes


def _walk(data, start: int = 0, end: Optional[int] = None):
    """Every complete box in data[start:end], as (kind, start, body, size, total)."""
    if end is None:
        end = len(data)
    end = min(end, len(data))
    position = start
    while position + 8 <= end:
        size, kind = struct.unpack(">I4s", data[position:position + 8])
        header = 8
        if size == 1:
            # A 64-bit size is legal MP4, even though this writer never needs it.
            if position + 16 > end:
                return
            size = struct.unpack(">Q", data[position + 8:position + 16])[0]
            header = 16
        if size < header or position + size > end:
            # Truncated, or smaller than its own header: the rest is not a box.
            return
        yield kind, position, position + header, size - header, size
        position += size


def boxes(data, start: int = 0, end: Optional[int] = None):
    """Every complete box in data[start:end], as (type, body offset, body size)."""
    for kind, _offset, body, size, _total in _walk(data, start, end):
        yield kind, body, size


def base_ns(init) -> Optional[int]:
    """The device time of the track's zero from the init segment, or None."""
    for kind, body, size in boxes(init, 0, len(init)):
        if kind != b"moov":
            continue
        for kind2, body2, size2 in boxes(init, body, body + size):
            if kind2 != b"udta":
                continue
            for kind3, body3, size3 in boxes(init, body2, body2 + size2):
                if kind3 != b"uuid" or size3 < 32 or init[body3:body3 + 16] != BASE_UUID:
                    continue
                version = struct.unpack(">I", init[body3 + 16:body3 + 20])[0]
                if version != 1:
                    continue
                return struct.unpack(">Q", init[body3 + 24:body3 + 32])[0]
    return None


def _read_all(source) -> bytes:
    if isinstance(source, (bytes, bytearray, memoryview)):
        return bytes(source)
    if isinstance(source, (str, os.PathLike)):
        with open(source, "rb") as handle:
            return handle.read()
    read = getattr(source, "read", None)
    if read is not None:
        return read()
    return b"".join(source)


def read_fmp4(source) -> Iterator[Box]:
    """Every complete top-level box in an init segment, a file, or a byte string.

    ``source`` may be ``bytes``, a path, a binary file-like object, or an
    iterable of byte chunks. The parser reads it to the end, so a live socket is
    better fed to the client's streaming command; this is for an init segment, a
    saved file, or an in-memory test vector. Incomplete boxes at the end are
    dropped, which is what makes a truncated fragment safe to parse.
    """
    data = _read_all(source)
    for kind, offset, body, size, total in _walk(data):
        yield Box(kind, offset, total, bytes(data[body:body + size]))
