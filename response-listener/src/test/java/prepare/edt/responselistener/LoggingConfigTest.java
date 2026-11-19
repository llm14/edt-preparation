package prepare.edt.responselistener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Checks the log settings in this app's own application.properties: ECS JSON in the packaged profile, fields written at the top
 * level (not under a nested "mdc" object), and plain text in dev and test. See openspec/specs/structured-logging.
 */
class LoggingConfigTest {

    private static final Properties PROPS = new Properties();

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = LoggingConfigTest.class.getResourceAsStream("/application.properties")) {
            assertNotNull(in, "application.properties is not on the classpath");
            PROPS.load(in);
        }
    }

    @Test
    void packagedProfileWritesEcsJson() {
        assertEquals("true", PROPS.getProperty("quarkus.log.console.json.enabled"));
        assertEquals("ecs", PROPS.getProperty("quarkus.log.console.json.log-format"));
    }

    @Test
    void applicationFieldsAreTopLevel() {
        assertEquals("true", PROPS.getProperty("quarkus.log.console.json.mdc.flat-fields"));
    }

    @Test
    void devAndTestStayPlainText() {
        assertEquals("false", PROPS.getProperty("%dev.quarkus.log.console.json.enabled"));
        assertEquals("false", PROPS.getProperty("%test.quarkus.log.console.json.enabled"));
    }
}
