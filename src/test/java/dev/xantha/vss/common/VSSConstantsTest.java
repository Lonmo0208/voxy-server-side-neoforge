package dev.xantha.vss.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VSSConstantsTest {

    @Test
    void protocolVersionIncludesLostCitiesHints() {
        assertEquals(48, VSSConstants.PROTOCOL_VERSION);
    }

    @Test
    void protocolRangeAcceptsLegacyClients() {
        assertEquals(43, VSSConstants.MIN_PROTOCOL_VERSION);
        assertTrue(VSSConstants.isProtocolCompatible(VSSConstants.MIN_PROTOCOL_VERSION));
        assertTrue(VSSConstants.isProtocolCompatible(VSSConstants.PROTOCOL_VERSION));
    }

    @Test
    void protocolOutsideRangeIsRejected() {
        assertFalse(VSSConstants.isProtocolCompatible(VSSConstants.MIN_PROTOCOL_VERSION - 1));
        assertFalse(VSSConstants.isProtocolCompatible(VSSConstants.PROTOCOL_VERSION + 1));
    }

    @Test
    void columnVersionIsStrictlyMonotonic() {
        long previous = VSSConstants.columnVersion();

        for (int i = 0; i < 1000; i++) {
            long next = VSSConstants.columnVersion();
            assertTrue(next > previous);
            previous = next;
        }
    }
}
