package io.github.mbanucu.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.Test;

public class ContentCallTest {

    @Test
    public void anExtraKeepsColonsAfterItsType() {
        ContentCall.Extra extra = ContentCall.Extra.parse("why:s:could not bind 127.0.0.1:7100");
        assertEquals("why", extra.name);
        assertEquals("s", extra.type);
        assertEquals("could not bind 127.0.0.1:7100", extra.value);
        assertEquals("could not bind 127.0.0.1:7100", extra.typed());
    }

    @Test
    public void extrasAreTypedTheWayThePlatformTypesThem() {
        assertEquals(0, ContentCall.Extra.parse("port:i:0").typed());
        assertEquals(0L, ContentCall.Extra.parse("offset:l:0").typed());
        assertEquals(2.5f, ContentCall.Extra.parse("scale:f:2.5").typed());
        assertEquals(2.5d, ContentCall.Extra.parse("scale:d:2.5").typed());
        assertEquals(Boolean.TRUE, ContentCall.Extra.parse("record:z:true").typed());
        assertEquals("a", ContentCall.Extra.parse("a:s:a").typed());
        assertEquals(null, ContentCall.Extra.parse("port:i:nope").typed());
    }

    @Test
    public void malformedExtrasAreRejectedWithTheGrammar() {
        for (String text : new String[]{"port", "port:0", ":i:0", "port:x:0"}) {
            try {
                ContentCall.Extra.parse(text);
                fail("accepted " + text);
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    @Test
    public void aShortCommandParses() {
        ContentCall.Call call = ContentCall.parse(
                "content call --uri content://x --method start --extra port:i:0");
        assertEquals("content://x", call.uri);
        assertEquals("start", call.method);
        assertEquals(1, call.extras.size());
        assertEquals("0", call.args().string("port"));
        assertEquals(0, call.args().intValue("port", 7100));
    }

    @Test
    public void aFullAdbCommandParses() {
        ContentCall.Call call = ContentCall.parse(
                "adb -s emulator-5554 shell content call --uri content://x "
                        + "--method status");
        assertEquals("status", call.method);
        assertTrue(call.extras.isEmpty());
    }

    @Test
    public void aMethodIsRequired() {
        try {
            ContentCall.parse("content call --uri content://x");
            fail("a command without --method parsed");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("no --method"));
        }
    }

    @Test
    public void commandRendersTheSameGrammarItParses() {
        String rendered = ContentCall.command("content://x", "start",
                ContentCall.Extra.parse("port:i:0"),
                ContentCall.Extra.parse("encoded_port:i:0"));
        ContentCall.Call call = ContentCall.parse(rendered);
        assertEquals("content://x", call.uri);
        assertEquals("start", call.method);
        assertEquals(2, call.extras.size());
    }

    @Test
    public void replyOutputIsParsedWithNoiseBeforeIt() {
        Reply reply = ContentCall.parseReply(
                "WARNING: linker: unused library\n"
                        + "Result: Bundle[{code=400, data=state=failed\nwhy=no frame\n}]");
        assertEquals(400, reply.code);
        assertEquals("state=failed\nwhy=no frame\n", reply.data);
        assertEquals("failed", ContentCall.fields(reply.data).get("state"));
    }

    @Test
    public void outputWithoutABundleIsAnError() {
        try {
            ContentCall.parseReply("Error: Unknown URI content://elsewhere\n");
            fail("non-Bundle output parsed");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("no Result"));
        }
    }

    @Test
    public void fieldsSplitOnTheFirstEqualsAndLastDuplicateWins() {
        Map<String, String> fields = ContentCall.fields(
                "\nnot a field\nstate=streaming\nwhy=a=b:c\nstate=stopped\n");
        assertEquals("stopped", fields.get("state"));
        assertEquals("a=b:c", fields.get("why"));
        assertFalse(fields.containsKey("not a field"));
    }

    @Test
    public void theSharedFixtureParsesIdenticallyInJava() throws Exception {
        Properties fixtures = new Properties();
        InputStream in = getClass().getClassLoader()
                .getResourceAsStream("control-call-fixtures.properties");
        assertNotNull("control-call-fixtures.properties missing", in);
        try {
            fixtures.load(in);
        } finally {
            in.close();
        }
        List<String> checked = new ArrayList<>();
        for (String key : fixtures.stringPropertyNames()) {
            if ("content-surface".equals(key)) {
                assertEquals(String.valueOf(ControlContract.SCHEMA_VERSION),
                        fixtures.getProperty(key));
                continue;
            }
            String[] expected = fixtures.getProperty(key).split("\\|", -1);
            ContentCall.Call call = ContentCall.parse(expected[0]);
            assertEquals("uri in " + key, expected[1], call.uri);
            assertEquals("method in " + key, expected[2], call.method);
            List<String> extras = new ArrayList<>();
            for (ContentCall.Extra extra : call.extras) {
                extras.add(extra.toString());
            }
            assertEquals("extras in " + key, expected[3], join(extras));
            checked.add(key);
        }
        assertTrue(checked.toString(), checked.size() >= 4);
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(value);
        }
        return out.toString();
    }
}
