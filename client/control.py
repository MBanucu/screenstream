#!/usr/bin/env python3
"""Drive screenstream's control surface from a host, over adb.

    python3 client/control.py status
    python3 client/control.py start --port 0 --encoded-port 0
    python3 client/control.py record
    python3 client/control.py recording
    python3 client/control.py stop

The provider replies with a Bundle whose ``code`` is 0 for a successful call and
400 for one that could not be done, and whose ``data`` is one ``key=value`` line
per field. ``content call`` prints that Bundle; :func:`parse_result` keeps the
parsing in one place so a test suite can reuse it. ``start`` is synchronous: it
returns ``state=streaming`` and the bound port after the first composited frame,
or ``code=400`` and ``state=failed`` with a ``why`` line.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from typing import Iterable, Sequence

AUTHORITY = "content://io.github.mbanucu.screenstream.control"
METHODS = ("status", "start", "stop", "record", "recording")

# `content call` prints `Result: Bundle[{code=0, data=...}]`; the data's newlines
# are literal, so the match has to span them.
_RESULT = re.compile(r"Result: Bundle\[\{code=(-?\d+), data=(.*)\}\]\s*$", re.S)


class ControlError(Exception):
    """The command failed before it produced a provider reply."""


class Reply:
    def __init__(self, code: int, data: str):
        self.code = code
        self.data = data

    @property
    def fields(self) -> dict[str, str]:
        return parse_fields(self.data)

    def __getitem__(self, key: str) -> str:
        return self.fields[key]

    def __repr__(self) -> str:
        return "Reply(code=%r, data=%r)" % (self.code, self.data)


def parse_fields(data: str) -> dict[str, str]:
    """Splits ``key=value`` lines; a value may itself contain ``=`` or ``:``."""
    fields: dict[str, str] = {}
    for line in data.splitlines():
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if key:
            # The last occurrence wins, like a shell reading the file.
            fields[key] = value.strip()
    return fields


def parse_result(text: str) -> Reply:
    """Parses the `content call` output, tolerating lines before the Result."""
    match = _RESULT.search(text)
    if not match:
        raise ControlError("no Result: Bundle in %r" % text.strip()[:500])
    return Reply(int(match.group(1)), match.group(2))


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
