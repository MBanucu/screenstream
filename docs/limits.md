# Limits and follow-ups

What the capture is not, and what is still open around it. The
[front page](../README.md) has the properties and the routes to the other
pages.

## Limits

- One capture per process, on the fixed port 7100.
- Frames are the display, not one app's window — a dialog in front is in the frame.
- No audio, no file output: this is a source, and a recorder is a different tool.
- `scale=2` is the compositor's, not a downscale afterwards, so it is cheap; there is
  nothing between full size and half size.

## Follow-ups for the control surface

The library module is extracted and screenstream is built on it, but issue #6 is
not finished:

- **Publish a version.** `io.github.mbanucu:android-control-surface` builds,
  tests and signs locally; no version of it is on Maven Central yet, so the
  coordinate in [library.md](library.md) is the intended one and the screenstream
  POM that names it cannot resolve from Central until it exists.
- **Migrate wireless-adb-keeper.** Its `ControlProvider`, `ControlReceiver` and
  `ControlToken` are the same code; move it onto the AAR and delete the copies.
- **One client.** Point `keeperctl.sh` at the shared Python client
  (`client/screenstream_client`) instead of its own `sed`/`awk` reply parser,
  and have adb-harness drive screenstream through the same client.
- **Token for screenstream itself.** The base supports one; screenstream
  deliberately configures none, so its existing replies and callers are
  unchanged. Turning it on is a separate decision.

## See also

[README](../README.md) · [client](client.md) · [app](app.md) · [library](library.md) · [control surface](control-surface.md) · [release](release.md) · [Termux build](termux-build.md) · [limits](limits.md)
