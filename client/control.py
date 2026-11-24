#!/usr/bin/env python3
"""Drive screenstream's control surface from a host, over adb.

    python3 client/control.py status
    python3 client/control.py grant
    python3 client/control.py start --port 0 --encoded-port 0
    python3 client/control.py record
    python3 client/control.py recording
    python3 client/control.py stop

The provider replies with a Bundle whose ``code`` is 0 for a successful call and
400 for one that could not be done, and whose ``data`` is one ``key=value`` line
per field. ``content call`` prints that Bundle; the parsing lives in
:mod:`screenstream_client.control` so the Java library and this client are
tested against the same fixture. ``start`` is synchronous: it returns
``state=streaming`` and the bound port after the first composited frame, or
``code=400`` and ``state=failed`` with a ``why`` line. A projection that was
never granted is ``state=waiting_for_grant`` with ``reason=dialog`` and the
``command`` that grants it; ``grant=yes|no|unknown`` is on every status and start
reply, so a harness can fail before recording a black video.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from typing import Iterable, Sequence

from screenstream_client.control import (  # noqa: F401  (re-exported for callers)
    SCHEMA_VERSION,
    Call,
    ControlError,
    Extra,
    Reply,
    parse_call,
    parse_extra,
    parse_fields,
    parse_result,
)

AUTHORITY = "content://io.github.mbanucu.screenstream.control"
METHODS = ("status", "grant", "start", "stop", "record", "recording")

# grant=yes means a projection would start without a dialog; no means it asks;
# unknown means the platform could not be asked. Absent is unknown too.
_GRANTED = {"yes": True, "no": False, "unknown": None}


def granted(reply: Reply) -> bool | None:
    """True for ``grant=yes``, False for ``grant=no``, None for unknown or absent."""
    return _GRANTED.get(reply.fields.get("grant", "unknown"), None)


def waiting_for_grant(reply: Reply) -> bool:
    """Whether a start returned because the projection grant is the missing piece."""
    return reply.fields.get("state") == "waiting_for_grant"


def command(method: str, serial: str | None = None,
            extras: Iterable[str] = ()) -> list[str]:
    """The adb command for one call, with extras already in KEY:TYPE:VALUE form."""
    argv = ["adb"]
    if serial:
        argv += ["-s", serial]
    argv += ["shell", "content", "call", "--uri", AUTHORITY, "--method", method]
    for extra in extras:
        argv += ["--extra", extra]
    return argv


def call(method: str, serial: str | None = None,
         extras: Iterable[str] = ()) -> Reply:
    done = subprocess.run(command(method, serial, extras),
                          capture_output=True, text=True)
    if done.returncode != 0:
        raise ControlError(done.stderr.strip() or "adb exited %d" % done.returncode)
    return parse_result(done.stdout)


def start_extras(port: int | None, encoded_port: int | None, scale: int | None,
                 timeout_ms: int | None) -> list[str]:
    extras: list[str] = []
    if port is not None:
        extras.append("port:i:%d" % port)
    if encoded_port is not None:
        extras.append("encoded_port:i:%d" % encoded_port)
    if scale is not None:
        extras.append("scale:i:%d" % scale)
    if timeout_ms is not None:
        extras.append("timeout_ms:i:%d" % timeout_ms)
    return extras


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("method", choices=METHODS)
    parser.add_argument("--serial", help="adb serial when more than one device is attached")
    parser.add_argument("--port", type=int,
                        help="raw stream port for start; 0 asks for an ephemeral one")
    parser.add_argument("--encoded-port", type=int,
                        help="encoded stream port for start; 0 asks for an ephemeral one")
    parser.add_argument("--scale", type=int, help="capture scale for start")
    parser.add_argument("--timeout-ms", type=int,
                        help="how long start waits for the first frame")
    parser.add_argument("--json", action="store_true",
                        help="print code, data and the parsed fields as JSON")
    args = parser.parse_args(argv)

    extras = start_extras(args.port, args.encoded_port, args.scale,
                          args.timeout_ms) if args.method == "start" else []
    try:
        reply = call(args.method, args.serial, extras)
    except ControlError as failed:
        print("control: %s" % failed, file=sys.stderr)
        return 2

    if args.json:
        print(json.dumps({"code": reply.code, "data": reply.data,
                          "fields": reply.fields}, indent=2, sort_keys=True))
    else:
        print(reply.data)
    return 0 if reply.code == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
