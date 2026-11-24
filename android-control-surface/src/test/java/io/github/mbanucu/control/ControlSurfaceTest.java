package io.github.mbanucu.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

public class ControlSurfaceTest {

    private static final int APP = 10142;

    private static final class MemoryStore implements ControlToken.Store {
        byte[] bytes;
        String path;

        @Override
        public byte[] read() {
            return bytes == null ? null : bytes.clone();
        }

        @Override
        public void write(byte[] raw) {
            bytes = raw.clone();
        }

        @Override
        public String describe() {
            return path;
        }
    }

    private static ControlSurface surface(boolean withToken) {
        MemoryStore store = new MemoryStore();
        store.path = "/data/token";
        ControlSurface.Builder builder = ControlSurface.builder(new ControlSurface.Handler() {
            @Override
            public Reply handle(String method, Args args) {
                if ("status".equals(method)) {
                    return Reply.of(0, "state=ready");
                }
                if ("echo".equals(method)) {
                    return Reply.keyValue(0, "got", args.string("value"));
                }
                return null;
            }
        }).gate(UidGate.defaults(APP)).usage("status, echo");
        if (withToken) {
            builder.token(ControlToken.of(store));
        }
        return builder.build();
    }

    @Test
    public void aPrivilegedCallerNeedsNoToken() {
        Reply reply = surface(false).dispatch(UidGate.SHELL, "status", Args.EMPTY);
        assertEquals(0, reply.code);
        assertEquals("state=ready", reply.data);
    }

    @Test
    public void anOrdinaryAppIsRefusedWith403() {
        Reply reply = surface(false).dispatch(APP + 1, "status", Args.EMPTY);
        assertEquals(403, reply.code);
        assertTrue(reply.data.startsWith("error=refused:"));
        assertEquals(ControlSurface.DEFAULT_REFUSAL, reply.data.substring("error=".length()));
    }

    @Test
    public void aProviderCanSpellItsOwnRefusal() {
        ControlSurface custom = ControlSurface.builder(new ControlSurface.Handler() {
            @Override
            public Reply handle(String method, Args args) {
                return Reply.of(0, "ok");
            }
        }).gate(UidGate.defaults(APP)).refusal("refused: shell only").build();
        assertEquals("error=refused: shell only",
                custom.dispatch(APP + 1, "status", Args.EMPTY).data);
    }

    @Test
    public void aValidTokenAdmitsAnOrdinaryApp() throws IOException {
        ControlSurface surface = surface(true);
        String token = surface.token().issue();
        Reply reply = surface.dispatch(APP + 1, "status",
                Args.builder().put(ControlContract.TOKEN, token).build());
        assertEquals(0, reply.code);
    }

    @Test
    public void aWrongOrMissingTokenIsTheSame403() {
        ControlSurface surface = surface(true);
        Reply missing = surface.dispatch(APP + 1, "status", Args.EMPTY);
        Reply wrong = surface.dispatch(APP + 1, "status",
                Args.builder().put(ControlContract.TOKEN, "0").build());
        assertEquals(403, missing.code);
        assertEquals(missing.data, wrong.data);
    }

    @Test
    public void onlyPrivilegedCallersMayMint() throws IOException {
        ControlSurface surface = surface(true);
        Reply minted = surface.dispatch(UidGate.SHELL, "token", Args.EMPTY);
        assertEquals(0, minted.code);
        String token = ContentCall.fields(minted.data).get("token");
        assertEquals(64, token.length());
        assertEquals("/data/token", ContentCall.fields(minted.data).get("path"));

        String held = surface.token().issue();
        Reply again = surface.dispatch(APP + 1, "token",
                Args.builder().put(ControlContract.TOKEN, held).build());
        assertEquals(403, again.code);
        assertTrue(again.data.contains("may only be requested from adb shell"));
    }

    @Test
    public void aMissingOrUnknownMethodIs400WithTheUsage() {
        ControlSurface surface = surface(false);
        Reply missing = surface.dispatch(UidGate.SHELL, null, Args.EMPTY);
        assertEquals(400, missing.code);
        assertEquals("error=missing method; use status, echo", missing.data);
        Reply unknown = surface.dispatch(UidGate.SHELL, "nope", Args.EMPTY);
        assertEquals(400, unknown.code);
        assertEquals("error=unknown method nope; use status, echo", unknown.data);
    }

    @Test
    public void extrasReachTheHandler() {
        Reply reply = surface(false).dispatch(UidGate.SHELL, "echo",
                Args.builder().put("value", "a=b").build());
        assertEquals("got=a=b", reply.data);
    }

    @Test
    public void aNegativeUidIsNeverPrivileged() {
        assertEquals(403, surface(false).dispatch(-1, "status", Args.EMPTY).code);
    }

    @Test
    public void aHandlerWithoutAPathStillMints() throws IOException {
        MemoryStore store = new MemoryStore();
        ControlSurface surface = ControlSurface.builder(new ControlSurface.Handler() {
            @Override
            public Reply handle(String method, Args args) {
                return null;
            }
        }).gate(UidGate.defaults(APP)).token(ControlToken.of(store)).build();
        Reply reply = surface.dispatch(UidGate.ROOT, "token", Args.EMPTY);
        assertEquals(0, reply.code);
        assertNull(ContentCall.fields(reply.data).get("path"));
        assertFalse(reply.data.contains("path="));
    }
}
