package prepare.edt.orchestrator.entity;

import java.util.List;

/**
 * An approved Flow (glossary): the Service Requests to send to one device, in order. DCC decides the real Flows; the two in
 * {@link FlowCatalogue} are drafts. Plain data, so the Flow Catalogue activity can return it to a workflow and Temporal keeps it
 * in the history.
 *
 * @param name  the name a Manifest row gives in its flow column
 * @param steps the Service Requests, in the order they are sent; never empty
 */
public record Flow(String name, List<Step> steps) {

    public Flow {
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("a Flow has at least one step: " + name);
        }
        steps = List.copyOf(steps);
    }

    /** One Service Request of a Flow.
     *
     * @param srv         the Service Reference Variant, for example 8.9
     * @param deviceTypes the device types it may be sent to (ESME, GSME, CHF ...)
     */
    public record Step(String srv, List<String> deviceTypes) {

        public Step {
            deviceTypes = List.copyOf(deviceTypes);
        }
    }

    /** Whether the Flow can run on a device of this type: its first step accepts it. */
    public boolean acceptsDeviceType(String deviceType) {
        return steps.get(0).deviceTypes().contains(deviceType);
    }
}
