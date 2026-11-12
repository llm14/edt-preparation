package prepare.edt.orchestrator.entity;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Facts: values taken from a Response that a Flow decides on (glossary), for example a register reading,
 * the payment mode or the firmware version. Name -> value, as text, sorted by name.
 *
 * Which Facts each Service Request yields depends on DCC's final Flows. [CONFIRM]
 */
public record Facts(Map<String, String> values) {

    public Facts {
        values = Collections.unmodifiableMap(new TreeMap<>(values));   // immutable, sorted: stable logs and JSON
    }

    public static Facts of(Map<String, String> values) {
        return new Facts(values);
    }

    public static Facts none() {
        return new Facts(Map.of());
    }
}
