package prepare.edt.responselistener.infrastructure;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.MDC;

/**
 * Adds key-value fields to every log line written inside a try block, then removes them.
 *
 * <pre>
 * try (var fields = LogFields.of("event.action", "startup")) {
 *     LOG.info("Response Listener started");
 * }
 * </pre>
 *
 * The fields go into the MDC (Mapped Diagnostic Context), which the JSON log writes as top-level fields (quarkus.log.console.json.mdc.flat-fields=true).
 */
public final class LogFields implements AutoCloseable {

    private final List<String> keys = new ArrayList<>();

    private LogFields() {
    }

    /** Takes pairs: key1, value1, key2, value2, ... Keys are text; a value of any type is written as its text. */
    public static LogFields of(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("LogFields.of needs key-value pairs");
        }
        LogFields fields = new LogFields();
        for (int i = 0; i < keyValues.length; i += 2) {
            fields.add(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return fields;
    }

    public LogFields add(String key, Object value) {
        MDC.put(key, String.valueOf(value));
        keys.add(key);
        return this;
    }

    @Override
    public void close() {
        keys.forEach(MDC::remove);
    }
}
