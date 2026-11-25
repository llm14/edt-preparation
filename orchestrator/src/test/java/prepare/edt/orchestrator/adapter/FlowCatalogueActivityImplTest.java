package prepare.edt.orchestrator.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.temporal.common.converter.DefaultDataConverter;
import org.junit.jupiter.api.Test;
import prepare.edt.orchestrator.adapter.FlowCatalogueActivity.Lookup;

class FlowCatalogueActivityImplTest {

    final FlowCatalogueActivityImpl activity = new FlowCatalogueActivityImpl();

    @Test
    void aKnownFlowIsReturnedWithItsSteps() {
        Lookup lookup = activity.lookup("meter-triage");

        assertTrue(lookup.found());
        assertEquals("meter-triage", lookup.flow().name());
        assertEquals(2, lookup.flow().steps().size());
    }

    @Test
    void anUnknownFlowIsANormalResultNotAFailure() {
        Lookup lookup = activity.lookup("flow-1");

        assertFalse(lookup.found());
        assertNull(lookup.flow());
    }

    @Test
    void theNameIsCaseSensitive() {
        assertFalse(activity.lookup("METER-TRIAGE").found());
    }

    @Test
    void theResultSurvivesTemporalsJsonConversionBecauseItIsStoredInTheHistory() {
        var converter = DefaultDataConverter.newDefaultInstance();
        Lookup original = activity.lookup("hub-triage");

        Lookup restored = converter.fromPayload(converter.toPayload(original).orElseThrow(), Lookup.class, Lookup.class);

        assertEquals(original, restored);
        assertEquals("6.30", restored.flow().steps().get(1).srv());
    }
}
