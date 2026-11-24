package io.github.mbanucu.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ArgsTest {

    @Test
    public void stringsAreReturnedAsSent() {
        Args args = Args.builder().put("why", "a=b:c").build();
        assertEquals("a=b:c", args.string("why"));
        assertTrue(args.has("why"));
        assertFalse(args.has("missing"));
    }

    @Test
    public void numbersAcceptTheirTypeAndADecimalString() {
        Args args = Args.builder()
                .put("number", 41)
                .put("text", " 42 ")
                .put("bad", "not a number")
                .build();
        assertEquals(41, args.intValue("number", 0));
        assertEquals(42, args.intValue("text", 0));
        assertEquals(7, args.intValue("bad", 7));
        assertEquals(42L, args.longValue("text", 0L));
        assertEquals(7L, args.longValue("bad", 7L));
    }

    @Test
    public void booleansAcceptBooleanNumberAndTrueOrFalseStrings() {
        Args args = Args.builder()
                .put("flag", true)
                .put("number", 2)
                .put("yes", " TRUE ")
                .put("no", "false")
                .build();
        assertTrue(args.booleanValue("flag", false));
        assertTrue(args.booleanValue("number", false));
        assertTrue(args.booleanValue("yes", false));
        assertFalse(args.booleanValue("no", true));
    }

    @Test
    public void anUnparseableBooleanUsesTheFallback() {
        Args args = Args.builder().put("flag", "sometimes").build();
        assertTrue(args.booleanValue("flag", true));
        assertFalse(args.booleanValue("flag", false));
        assertTrue(args.booleanValue("missing", true));
    }
}
