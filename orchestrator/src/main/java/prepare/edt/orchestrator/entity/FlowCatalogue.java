package prepare.edt.orchestrator.entity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import prepare.edt.orchestrator.entity.Flow.Step;

/**
 * The Flow Catalogue (model 010.020): the Flows the EDT can run. DRAFT content, written from the list of candidate Service
 * Requests DCC gave (samples/possible_flows.csv, samples/draft_flows.md). [CONFIRM] DCC provides the three approved Flows, their
 * steps, order and decisions; the names below are ours.
 *
 *   hub-triage    a Communications Hub: 8.9 Read Device Log, then 6.30 Read CHF Sub GHz Configuration
 *   meter-triage  an ESME or GSME: 6.2.9 Read Device Configuration (Payment Mode), then 11.2 Read Firmware Version
 *
 * Names are case-sensitive. After each step the Flow continues only if the step got a Response (the rule is in the Flow workflow).
 */
public final class FlowCatalogue {

    public static final String HUB_TRIAGE = "hub-triage";
    public static final String METER_TRIAGE = "meter-triage";

    private static final Map<String, Flow> FLOWS = Map.of(
            HUB_TRIAGE, new Flow(HUB_TRIAGE, List.of(
                    new Step("8.9", List.of("CHF")),
                    new Step("6.30", List.of("CHF")))),
            METER_TRIAGE, new Flow(METER_TRIAGE, List.of(
                    new Step("6.2.9", List.of("ESME", "GSME")),
                    new Step("11.2", List.of("ESME", "GSME", "CHF")))));

    private FlowCatalogue() {
    }

    /** The Flow with this name, or empty when the catalogue has none (a Flow not in the catalogue is rejected). */
    public static Optional<Flow> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(FLOWS.get(name));   // an immutable Map refuses a null key
    }

    /** The names of all Flows, sorted. */
    public static List<String> names() {
        return FLOWS.keySet().stream().sorted().toList();
    }
}
