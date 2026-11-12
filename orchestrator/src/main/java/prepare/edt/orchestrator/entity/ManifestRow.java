package prepare.edt.orchestrator.entity;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * One device row of a Manifest. PLACEHOLDER format until DCC confirms it: CSV "device_id,device_type,flow". [CONFIRM]
 *
 * @param rowNumber  1 for the first row after the header; gives each device Flow its own workflow ID
 *                   (the same device may appear twice in a Manifest)
 * @param deviceId   for example 00-DB-12-34-56-78-90-A0
 * @param deviceType for example ESME, GSME, CHF
 * @param flow       which approved Flow to run (placeholder names)
 */
public record ManifestRow(long rowNumber, String deviceId, String deviceType, String flow) {

    public static final String HEADER = "device_id,device_type,flow";

    /** Eight hex pairs separated by dashes: the EUI-64 form of a device ID. */
    private static final Pattern DEVICE_ID = Pattern.compile("[0-9A-F]{2}(-[0-9A-F]{2}){7}");

    /** PLACEHOLDER list of device types. [CONFIRM] */
    private static final Set<String> DEVICE_TYPES = Set.of("ESME", "GSME", "CHF", "PPMID", "HCALCS");

    /**
     * Reads one CSV line.
     *
     * @throws InvalidRowException with the reason (no row content in it, so it can be logged safely)
     */
    public static ManifestRow parse(long rowNumber, String line) throws InvalidRowException {
        String[] columns = line.split(",", -1);
        if (columns.length != 3) {
            throw new InvalidRowException("expected 3 columns, found " + columns.length);
        }
        String deviceId = columns[0].trim();
        String deviceType = columns[1].trim();
        String flow = columns[2].trim();
        if (!DEVICE_ID.matcher(deviceId).matches()) {
            throw new InvalidRowException("device_id is not a device ID");
        }
        if (!DEVICE_TYPES.contains(deviceType)) {
            throw new InvalidRowException("unknown device_type");
        }
        if (flow.isEmpty()) {
            throw new InvalidRowException("flow is empty");
        }
        return new ManifestRow(rowNumber, deviceId, deviceType, flow);
    }

    public static final class InvalidRowException extends Exception {
        public InvalidRowException(String reason) {
            super(reason);
        }
    }
}
