package prepare.edt.dcctools.dspsimulator;

import java.util.List;

/**
 * What the simulated DSP and device do with a Service Request, chosen by the LAST GROUP of the target device ID.
 * Invented for this learning project: real devices have no such codes.
 *
 *   ...-A0  accepted, then a Response            (also the default for any other ending)
 *   ...-A1  accepted, then DCC Alert N12
 *   ...-A2  accepted, then DCC Alert N13
 *   ...-A3  accepted, then nothing (the EDT times out)
 *   ...-A4  rejected in the synchronous response
 *   ...-A5  accepted, then DCC Alert N53, then a Response
 */
enum DeviceBehaviour {

    RESPOND(true, List.of("RESPONSE")),
    ALERT_N12(true, List.of("N12")),
    ALERT_N13(true, List.of("N13")),
    SILENT(true, List.of()),
    REJECT(false, List.of()),
    ALERT_N53_THEN_RESPOND(true, List.of("N53", "RESPONSE"));

    /** Whether the synchronous response accepts the Service Request. */
    final boolean accepted;

    /** What is posted later to the Response Listener, in order: "RESPONSE" or a DCC Alert code. */
    final List<String> asyncMessages;

    DeviceBehaviour(boolean accepted, List<String> asyncMessages) {
        this.accepted = accepted;
        this.asyncMessages = asyncMessages;
    }

    static DeviceBehaviour forTarget(String targetDevice) {
        String last = targetDevice.substring(targetDevice.lastIndexOf('-') + 1).toUpperCase();
        return switch (last) {
            case "A1" -> ALERT_N12;
            case "A2" -> ALERT_N13;
            case "A3" -> SILENT;
            case "A4" -> REJECT;
            case "A5" -> ALERT_N53_THEN_RESPOND;
            default -> RESPOND;
        };
    }
}
