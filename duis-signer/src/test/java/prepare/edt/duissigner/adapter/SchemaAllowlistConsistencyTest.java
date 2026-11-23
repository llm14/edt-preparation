package prepare.edt.duissigner.adapter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The partial schema and the allowlist are two lists of the same thing: the SRVs the EDT can send. A request whose SRV the
 * schema accepts but no allowlist has is refused by the policy, so the Flow would never get it signed; this test fails
 * when that happens, for example when an SRV is added to the schema and not to the configuration.
 *
 * One direction only: the allowlist may hold an SRV the schema does not know (the placeholder 6.15.1 of the Registered
 * Supplier Agent exercises role selection); such a request is refused by the schema check.
 */
class SchemaAllowlistConsistencyTest {

    static final Pattern ALLOWLIST_KEY = Pattern.compile("edt\\.policy\\.roles\\.[^.]+\\.srvs\\.\"([^\"]+)\"");

    @Test
    void every_srv_the_schema_accepts_is_on_an_allowlist() throws Exception {
        Set<String> inSchema = srvsInSchema();
        Set<String> inAllowlist = srvsInAllowlist();

        assertFalse(inSchema.isEmpty(), "the schema lists no SRV: the test would prove nothing");
        assertTrue(inAllowlist.containsAll(inSchema),
                "SRVs in the schema but on no allowlist: " + difference(inSchema, inAllowlist));
    }

    @Test
    void the_srvs_of_the_placeholder_flow_are_in_both() throws Exception {
        assertTrue(srvsInSchema().containsAll(Set.of("4.1.1", "8.9")));
        assertTrue(srvsInAllowlist().containsAll(Set.of("4.1.1", "8.9")));
    }

    static Set<String> srvsInSchema() throws Exception {
        try (InputStream xsd = SchemaAllowlistConsistencyTest.class.getResourceAsStream("/schema/service-request.xsd")) {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            var document = factory.newDocumentBuilder().parse(xsd);
            NodeList types = document.getElementsByTagNameNS("http://www.w3.org/2001/XMLSchema", "simpleType");
            Set<String> srvs = new TreeSet<>();
            for (int i = 0; i < types.getLength(); i++) {
                Element type = (Element) types.item(i);
                if ("ServiceReferenceVariant".equals(type.getAttribute("name"))) {
                    NodeList values = type.getElementsByTagNameNS("http://www.w3.org/2001/XMLSchema", "enumeration");
                    for (int j = 0; j < values.getLength(); j++) {
                        srvs.add(((Element) values.item(j)).getAttribute("value"));
                    }
                }
            }
            return srvs;
        }
    }

    static Set<String> srvsInAllowlist() throws Exception {
        Properties properties = new Properties();
        try (InputStream in = SchemaAllowlistConsistencyTest.class.getResourceAsStream("/application.properties")) {
            properties.load(in);
        }
        Set<String> srvs = new TreeSet<>();
        for (String key : properties.stringPropertyNames()) {
            Matcher matcher = ALLOWLIST_KEY.matcher(key);
            if (matcher.matches()) {
                srvs.add(matcher.group(1));
            }
        }
        return srvs;
    }

    static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.removeAll(right);
        return result;
    }
}
