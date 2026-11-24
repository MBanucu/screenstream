"""The ``content call`` wire format, shared by every consumer of the surface.

The Java library ``android-control-surface`` implements the same two shapes and
is tested against the same fixture (``control-call-fixtures.properties``), so a
change to the grammar has to be made in both places deliberately.

A call is::

    adb [-s SERIAL] shell content call --uri URI --method METHOD \\
        [--extra name:type:value]...

The reply is a Bundle printed as::

    Result: Bundle[{code=0, data=key=value
    key=value
    }]

``code`` is 0 for success, 400 for a call that was understood but could not be
done, and 403 for a caller that is not allowed. ``data`` is one ``key=value``
line per field, split on the first ``=``; a value never contains a newline.
"""

from __future__ import annotations

import re
import shlex
from dataclasses import dataclass
from typing import Iterable, Mapping

#: The version of the reply/extras grammar. Java's ControlContract.SCHEMA_VERSION
#: must match; test/test_control.py reads that constant from the Java source.
SCHEMA_VERSION = 1

TYPES = "silfdz"

# `content call` prints `Result: Bundle[{code=0, data=...}]`; the data's newlines
# are literal, so the match has to span them.
_RESULT = re.compile(r"Result: Bundle\[\{code=(-?\d+), data=(.*)\}\]\s*$", re.S)


class ControlError(Exception):
    """The command failed before it produced a provider reply."""


@dataclass(frozen=True)
class Extra:
    """One ``--extra name:type:value`` with the platform's type letter."""

    name: str
    type: str
    value: str

    @classmethod
    def parse(cls, text: str) -> "Extra":
        name, sep, rest = text.partition(":")
        type_, sep2, value = rest.partition(":")
        if not sep or not sep2 or not name:
            raise ValueError("extra %r is not name:type:value" % text)
        if len(type_) != 1 or type_ not in TYPES:
            raise ValueError("unknown extra type %r; use s, i, l, f, d or z"
                             % type_)
        return cls(name, type_, value)

    def typed(self):
        """The value as its type names it, or None when it does not parse."""
        text = self.value.strip()
        try:
            if self.type == "i" or self.type == "l":
                return int(text)
            if self.type in ("f", "d"):
                return float(text)
            if self.type == "z":
                return text.lower() == "true"
        except ValueError:
            return None
        return self.value

    def __str__(self) -> str:
        return "%s:%s:%s" % (self.name, self.type, self.value)


@dataclass(frozen=True)
class Call:
    """A parsed call: where it goes, what it asks for, and its extras."""

    uri: str | None
    method: str
    extras: tuple[Extra, ...]

    def args(self) -> dict[str, object]:
        out: dict[str, object] = {}
        for extra in self.extras:
            typed = extra.typed()
            out[extra.name] = extra.value if typed is None else typed
        return out


def parse_extra(text: str) -> Extra:
    """Parses ``name:type:value``; a value may itself contain ``:``."""
    return Extra.parse(text)


def parse_call(command: str | Iterable[str]) -> Call:
    """Parses a short or full ``adb ... shell content call ...`` command."""
    tokens = shlex.split(command) if isinstance(command, str) else list(command)
    uri = None
    method = None
    extras: list[Extra] = []
    index = 0
    while index < len(tokens):
        token = tokens[index]
        if token == "--uri" and index + 1 < len(tokens):
            index += 1
            uri = tokens[index]
        elif token == "--method" and index + 1 < len(tokens):
            index += 1
            method = tokens[index]
        elif token == "--extra" and index + 1 < len(tokens):
            index += 1
            extras.append(Extra.parse(tokens[index]))
        index += 1
    if method is None:
        raise ValueError("no --method in %r" % command)
    return Call(uri, method, tuple(extras))


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


def fields_match(mapping: Mapping[str, object]) -> str:
    """Renders fields the way a provider does; useful for tests and fixtures."""
    return "\n".join("%s=%s" % (key, str(value).replace("\n", " ").replace("\r", " "))
                     for key, value in mapping.items())
