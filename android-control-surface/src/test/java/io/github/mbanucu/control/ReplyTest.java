package io.github.mbanucu.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class ReplyTest {

    @Test
    public void keyValueJoinsOneFieldPerLine() {
        Reply reply = Reply.keyValue(ControlContract.OK,
                "state", "streaming", "port", 43127);
        assertEquals(0, reply.code);
        assertEquals("state=streaming\nport=43127", reply.data);
    }

    @Test
    public void errorAndRefusalRepliesHaveNoTrailingNewline() {
        assertEquals("error=missing method",
                Reply.badRequest("missing method").data);
        assertEquals(403, Reply.forbidden("refused").code);
        assertEquals("error=refused", Reply.forbidden("refused").data);
    }

    @Test
    public void oneLineReplacesOnlyCarriageReturnAndNewline() {
        // CRLF is two characters and becomes two spaces; the old screenstream
        // helper has always done that, and this library must not change it.
        assertEquals("a b  c", Reply.oneLine("a\nb\r\nc"));
        assertEquals("path=/sdcard/a=b:c d", Reply.oneLine("path=/sdcard/a=b:c\nd"));
        assertEquals("", Reply.oneLine(null));
        assertEquals("42", Reply.oneLine(42));
        assertEquals("true", Reply.oneLine(true));
    }

    @Test
    public void aValueMayKeepEqualsAndColons() {
        Reply reply = Reply.keyValue(400,
                "why", "could not listen on 127.0.0.1:7100: Address already in use",
                "recording", "/sdcard/a=b/c.mp4");
        assertEquals("why=could not listen on 127.0.0.1:7100: Address already in use\n"
                + "recording=/sdcard/a=b/c.mp4", reply.data);
    }

    @Test
    public void lineMatchesTheLegacyScreenstreamAppendStyle() {
        Reply idle = Reply.of(0, "state=stopped\n");
        assertEquals("state=stopped\nport=7100\n", idle.line("port", 7100).data);
        Reply noNewline = Reply.of(0, "state=stopped");
        assertEquals("state=stopped\nport=7100\n", noNewline.line("port", 7100).data);
    }

    @Test
    public void mapFormKeepsIterationOrder() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("grant", "yes");
        fields.put("grant_route", "appop");
        Reply reply = Reply.keyValue(0, fields);
        assertEquals("grant=yes\ngrant_route=appop", reply.data);
    }

    @Test
    public void oddPairsAreRefused() {
        try {
            Reply.keyValue(0, "state");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("alternating"));
            return;
        }
        throw new AssertionError("odd key/value list was accepted");
    }

    @Test
    public void okIsTheSuccessfulCode() {
        assertTrue(Reply.of(0, "").ok());
        assertFalse(Reply.of(400, "").ok());
        assertEquals(0, ControlContract.OK);
        assertEquals(400, ControlContract.BAD_REQUEST);
        assertEquals(403, ControlContract.FORBIDDEN);
    }
}
