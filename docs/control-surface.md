# The control surface contract — schema 1

This is the frozen contract between a control surface (an Android
`ContentProvider` and its ordered-broadcast twin) and the clients that drive it.
The implementation is the `android-control-surface` AAR in this repository; the
same document covers screenstream today and wireless-adb-keeper after its
migration.

A change here is a schema change. Adding a new `data` field is compatible and
does not need one; renaming a field, changing a code, changing the escaping or
changing the extras grammar does.

## Schema and version

| item | value |
|---|---|
| schema | **1** |
| Java constant | `io.github.mbanucu.control.ControlContract.SCHEMA_VERSION` |
| Python constant | `screenstream_client.control.SCHEMA_VERSION` |
| shared fixture | `android-control-surface/src/test/resources/control-call-fixtures.properties` |

The schema is a library constant, not a reply field. A provider that wants to
advertise it may add a `schema=1` line to `data`; screenstream deliberately does
not, because its replies predate the library and existing readers must stay
byte-for-byte compatible. The Python test reads the Java constant and fails if
the two drift.

## Transport

### Provider (shell, root, system, self)

```bash
adb shell content call --uri content://<app>.control --method status
adb shell content call --uri content://<app>.control --method start \
    --extra port:i:0 --extra encoded_port:i:0
```

The provider is started on demand by the platform, so `status` answers from a
fresh process, including after a reboot, without the app having been launched.

### Ordered broadcast (ordinary app uid)

`content call` needs `ACCESS_CONTENT_PROVIDERS_EXTERNALLY`, a signature
permission held only by shell and root, so an ordinary app cannot use it. The
twin is an explicit ordered broadcast:

```bash
am broadcast --user 0 -n <app>/.ControlReceiver -a <app>.CONTROL \
    --es method status --es token <token>
```

The receiver runs the same dispatch and sets the reply as the broadcast result:
`result=<code>` and `data="<data>"` on the `Broadcast completed` line. A
`BroadcastReceiver` does not expose a trustworthy sender uid, so the token is the
whole gate on this path; shell and root keep their uid gate through the provider.

## The reply

The provider returns a `Bundle` with exactly two keys:

| key | type | meaning |
|---|---|---|
| `code` | `int` | `0` success, `400` understood but not done, `403` caller not allowed |
| `data` | `String` | zero or more `key=value` lines |

`500` is reserved for an unexpected failure inside the library itself; it is not
part of the success/bad-request/refusal contract and no client should need it.

`content call` prints the Bundle as:

```
Result: Bundle[{code=0, data=state=streaming
port=43127
protocol=2.0
}]
```

A reader finds the `Result: Bundle[...]` suffix and tolerates lines before it
(a linker warning, for instance).

### `data` grammar

- One `key=value` per line, separated by `\n`.
- The **first** `=` splits key from value, so a value may contain `=`, `:` and
  spaces.
- Keys are trimmed; a blank key is skipped; a line without `=` is skipped.
- The **last** occurrence of a key wins, like a shell reading the state file.
- Values are trimmed at both ends by the reader; the writer does not add padding.
- A value never contains a newline: the writer replaces `\n` and `\r` with a
  single space each. There is no other escaping and no quoting. A `CRLF` value
  therefore becomes two spaces; that is the historical screenstream behaviour and
  is part of schema 1.

### `code`

| code | when |
|---|---|
| `0` | the method ran and its result is in `data` |
| `400` | the method is missing, unknown, or could not do what was asked |
| `403` | the caller is not in the uid allowlist and has no valid token |
| `500` | reserved: the library caught an unexpected runtime failure |

A failure carries an `error=` line when the cause is the surface itself (a
refused extra, a missing recording directory); a method-specific failure may
carry `state=failed` plus `why=` instead.

## Extras

```
--extra name:type:value
```

`type` is one of the platform `content` command's letters:

| type | value |
|---|---|
| `s` | string |
| `i` | int |
| `l` | long |
| `f` | float |
| `d` | double |
| `z` | boolean (`true`/`false`) |

The value is everything after the second `:`, so `--extra why:s:a:b` is the
string `a:b`. A malformed extra (no name, no type, unknown type) is a `400`.

A typed getter falls back rather than throwing: a missing extra, or a string that
is not a number, yields the caller's default. `i` also accepts a string, so
`--extra port:s:0` is not silently replaced by the default port.

The positional `call(method, arg, extras)` argument is available as `arg` when no
`arg` extra was supplied.

## Methods

Schema 1 does not fix an app's method names; it fixes their *shape*. A provider
declares its usage list in the `400` hint.

The library reserves one optional method:

| method | callers | reply |
|---|---|---|
| `token` | shell, root, system only | `token=<64 hex>` and, when the store has a path, `path=<file>` |

An ordinary app holding a token may not call `token`: a token controls the app,
it does not bootstrap another copy of itself. A provider with no token configured
has no `token` method.

### screenstream's methods

| method | extras | reply |
|---|---|---|
| `status` | — | state file vocabulary, plus `grant`/`grant_route` |
| `grant` | — | `grant`, `grant_route`, `package`, and when not granted `reason`, `command`, `fallback`, `revoke_command` |
| `start` | `port`, `encoded_port`, `scale`, `timeout_ms`, `record` | `state=streaming` after the first composited frame, or `state=failed`/`state=waiting_for_grant` |
| `stop` | — | final state; `recording_file` when a recording was being written |
| `record` | — | the open recording's path in `recording` |
| `recording` | — | ends the recording, waits for the index, adds `recording_file` |

`start` with `port:i:0` asks the kernel for an ephemeral port and reports the one
it bound. A still screen still produces composited frames, so the synchronous
answer does not depend on the screen changing.

## Caller gate and token

The default allowlist is the four identities every adb surface in this family has
used:

| uid | identity |
|---|---|
| `0` | root |
| `1000` | system |
| `2000` | shell |
| `Process.myUid()` | the app itself |

Any other uid is `403` unless it presents a valid token in `--extra token:s:...`.
A missing token, a wrong token and a malformed token all produce the same `403`
and the same `data`, so a guess learns nothing.

The token is:

- 32 random bytes, carried as 64 lowercase hex characters;
- stored in a 0600 file (or an app-defined store) that an ordinary app cannot
  read;
- minted only by the shell-only `token` method, never as a side effect of a
  request;
- verified in constant time, and never minted by a failed verification.

A short or empty token file is reported as broken rather than replaced: deleting
the file is the documented way to mint a new one.

## One parser, two implementations

The grammar above is implemented once per language, and both implementations are
tested against the same fixture:

- Java: `io.github.mbanucu.control.ContentCall` — `parse`, `parseReply`,
  `fields`, `Extra.parse`, `command`.
- Python: `screenstream_client.control` — `parse_call`, `parse_result`,
  `parse_fields`, `parse_extra`.

`test/test_control.py` and `ContentCallTest` read
`control-call-fixtures.properties`; one side cannot change the grammar without
the other side's test failing. Wireless-adb-keeper's `keeperctl.sh` is a shell
consumer of the same reply grammar; the follow-up is to point it at the shared
Python client instead of its own `sed`/`awk` parser.

## Follow-ups (not in this change)

- Publish `io.github.mbanucu:android-control-surface` to Maven Central (a
  version number is permanent; this repository has not released one yet).
- Migrate wireless-adb-keeper's `ControlProvider`/`ControlReceiver`/
  `ControlToken` onto the library and delete its copies.
- Point `keeperctl.sh` and adb-harness at the shared Python client instead of
  their own reply parsers.
- Decide whether screenstream itself should accept a token; it currently has
  none, and schema 1 keeps its existing replies unchanged.
