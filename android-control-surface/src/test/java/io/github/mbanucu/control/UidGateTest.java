package io.github.mbanucu.control;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UidGateTest {

    private static final int APP = 10142;

    @Test
    public void theFourStandardIdentitiesArePrivileged() {
        UidGate gate = UidGate.defaults(APP);
        assertTrue(gate.privileged(UidGate.ROOT));
        assertTrue(gate.privileged(UidGate.SYSTEM));
        assertTrue(gate.privileged(UidGate.SHELL));
        assertTrue(gate.privileged(APP));
    }

    @Test
    public void anOrdinaryAppAndAnUnknownSenderAreNot() {
        UidGate gate = UidGate.defaults(APP);
        assertFalse(gate.privileged(10143));
        assertFalse(gate.privileged(-1));
    }

    @Test
    public void everyIdentityCanBeTurnedOff() {
        UidGate gate = UidGate.builder(APP)
                .allowRoot(false)
                .allowSystem(false)
                .allowShell(false)
                .allowSelf(false)
                .build();
        assertFalse(gate.privileged(UidGate.ROOT));
        assertFalse(gate.privileged(UidGate.SYSTEM));
        assertFalse(gate.privileged(UidGate.SHELL));
        assertFalse(gate.privileged(APP));
    }

    @Test
    public void selfIsTheConfiguredUidOnly() {
        UidGate gate = UidGate.builder(APP).allowSelf(true).build();
        assertTrue(gate.privileged(APP));
        assertFalse(gate.privileged(APP + 1));
        assertFalse(UidGate.builder(APP).allowSelf(false).build().privileged(APP));
    }
}
