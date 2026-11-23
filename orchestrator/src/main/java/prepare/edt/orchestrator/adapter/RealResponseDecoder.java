package prepare.edt.orchestrator.adapter;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import prepare.edt.orchestrator.entity.Facts;
import prepare.edt.orchestrator.usecase.ResponseDecoder;

/**
 * Reads the decoded Response (MMC XML, what Parsec yields from the GBCS payload) of the two SRVs we send:
 * 4.1.1 (Read Instantaneous Import Registers, electricity and gas) and 8.9 (Read Device Log).
 * Written from the released RTDS 4.10.0 examples (ECS17b, GCS13a, CCS06). The Facts are listed in design.md D4.
 * The device type of a 4.1.1 Response is read from the content (the Electricity or Gas element).
 */
public class RealResponseDecoder implements ResponseDecoder {

    static final String NAMESPACE = "http://www.dccinterface.co.uk/ResponseAndAlert";

    private interface Reader {
        void read(Element response, Map<String, String> facts);
    }

    /** Per SRV: the name of the element that holds the content, and what reads it. */
    private record Decoding(String responseElement, Reader reader) {}

    private final Map<String, Decoding> decodings = Map.of(
            "4.1.1", new Decoding("ReadInstantaneousImportRegistersRsp", RealResponseDecoder::readRegisters),
            "8.9", new Decoding("ReadDeviceLogRsp", RealResponseDecoder::readDeviceLog));

    @Override
    public Facts decode(String srv, String responseXml) {
        Document document = parse(responseXml);
        Decoding decoding = decodings.get(srv);
        if (decoding == null) {
            throw new UndecodableResponseException("no decoding for SRV " + srv, null);
        }
        Element root = document.getDocumentElement();
        if (!NAMESPACE.equals(root.getNamespaceURI()) || !"GBCSResponse".equals(root.getLocalName())) {
            throw new UndecodableResponseException("not a GBCSResponse", null);
        }
        var found = root.getElementsByTagNameNS(NAMESPACE, decoding.responseElement());
        if (found.getLength() != 1) {
            throw new UndecodableResponseException("the Response has no " + decoding.responseElement(), null);
        }
        Element response = (Element) found.item(0);

        Map<String, String> facts = new LinkedHashMap<>();
        boolean success = "true".equals(response.getAttribute("MessageSuccess"));
        facts.put("MessageSuccess", String.valueOf(success));
        decoding.reader().read(response, facts);
        if (!success) {
            String summary = debugSummary(root);
            if (!summary.isEmpty()) {
                facts.put("DebugSummary", summary);
            }
        }
        return Facts.of(facts);
    }

    /** 4.1.1: each register under Electricity or Gas that has a Value is a Fact, with its unit. */
    private static void readRegisters(Element response, Map<String, String> facts) {
        for (Element device : children(response)) {
            if (!"Electricity".equals(device.getLocalName()) && !"Gas".equals(device.getLocalName())) {
                continue;
            }
            for (Element register : children(device)) {
                String value = text(register, "Value");
                if (value == null) {
                    continue;                                   // a failed read carries no value: no Fact
                }
                String name = register.getLocalName();
                facts.put(name, value);
                for (Element part : children(register)) {
                    if (part.getLocalName().endsWith("Unit")) {
                        facts.put(name + ".Unit", part.getTextContent().trim());
                    }
                }
            }
        }
    }

    /** 8.9: one pair of Facts per device in the log. */
    private static void readDeviceLog(Element response, Map<String, String> facts) {
        for (Element log : children(response)) {
            for (Element entry : children(log)) {
                String deviceId = text(entry, "DeviceID");
                if (deviceId == null) {
                    continue;
                }
                putIfPresent(facts, "CHFDeviceLog." + deviceId + ".LastCommunicationsDateTime",
                        text(entry, "LastCommunicationsDateTime"));
                putIfPresent(facts, "CHFDeviceLog." + deviceId + ".SubGHzLinkQuality", text(entry, "SubGHzLinkQuality"));
            }
        }
    }

    /** The distinct COSEM and ZigBee result strings that are not a success, in order, joined with a comma. */
    private static String debugSummary(Element root) {
        Set<String> results = new LinkedHashSet<>();
        for (String name : List.of("ActionResult", "ZCLStatus")) {
            var nodes = root.getElementsByTagNameNS(NAMESPACE, name);
            for (int i = 0; i < nodes.getLength(); i++) {
                String result = nodes.item(i).getTextContent().trim();
                if (!result.isEmpty() && !"success".equalsIgnoreCase(result)) {
                    results.add(result);
                }
            }
        }
        return String.join(",", results);
    }

    private static void putIfPresent(Map<String, String> facts, String name, String value) {
        if (value != null) {
            facts.put(name, value);
        }
    }

    private static String text(Element parent, String childName) {
        for (Element child : children(parent)) {
            if (childName.equals(child.getLocalName())) {
                return child.getTextContent().trim();
            }
        }
        return null;
    }

    private static List<Element> children(Element parent) {
        List<Element> list = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                list.add(e);
            }
        }
        return list;
    }

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new UndecodableResponseException("not well-formed XML: " + e.getMessage(), e);
        }
    }
}
