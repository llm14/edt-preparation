package prepare.edt.orchestrator.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import prepare.edt.orchestrator.entity.Flow.Step;

/** The two DRAFT Flows, written from samples/possible_flows.csv. */
class FlowCatalogueTest {

    @Test
    void hubTriageSendsTheDeviceLogThenTheSubGhzConfigurationToAHub() {
        Flow flow = FlowCatalogue.find("hub-triage").orElseThrow();

        assertEquals("hub-triage", flow.name());
        assertEquals(List.of("8.9", "6.30"), flow.steps().stream().map(Step::srv).toList());
        assertEquals(List.of("CHF"), flow.steps().get(0).deviceTypes());
        assertEquals(List.of("CHF"), flow.steps().get(1).deviceTypes());
    }

    @Test
    void meterTriageSendsThePaymentModeThenTheFirmwareVersionToAMeter() {
        Flow flow = FlowCatalogue.find("meter-triage").orElseThrow();

        assertEquals(List.of("6.2.9", "11.2"), flow.steps().stream().map(Step::srv).toList());
        assertEquals(List.of("ESME", "GSME"), flow.steps().get(0).deviceTypes());
        assertEquals(List.of("ESME", "GSME", "CHF"), flow.steps().get(1).deviceTypes());
    }

    @Test
    void aFlowAcceptsADeviceTypeWhenItsFirstStepDoes() {
        Flow hub = FlowCatalogue.find("hub-triage").orElseThrow();
        Flow meter = FlowCatalogue.find("meter-triage").orElseThrow();

        assertTrue(hub.acceptsDeviceType("CHF"));
        assertFalse(hub.acceptsDeviceType("ESME"));
        assertTrue(meter.acceptsDeviceType("ESME"));
        assertTrue(meter.acceptsDeviceType("GSME"));
        assertFalse(meter.acceptsDeviceType("CHF"), "11.2 accepts a hub, but the Flow starts with 6.2.9");
    }

    @Test
    void aFlowThatIsNotInTheCatalogueIsNotFoundAndNamesAreCaseSensitive() {
        assertTrue(FlowCatalogue.find("flow-1").isEmpty(), "the placeholder name of the old sample Manifests");
        assertTrue(FlowCatalogue.find("Hub-Triage").isEmpty());
        assertTrue(FlowCatalogue.find("").isEmpty());
        assertTrue(FlowCatalogue.find(null).isEmpty());
    }

    @Test
    void theCatalogueHasTheTwoDraftFlows() {
        assertEquals(List.of("hub-triage", "meter-triage"), FlowCatalogue.names());
    }

    @Test
    void aFlowWithoutStepsCannotBeCreated() {
        assertThrows(IllegalArgumentException.class, () -> new Flow("empty", List.of()));
    }
}
