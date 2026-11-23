package prepare.edt.orchestrator.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import prepare.edt.orchestrator.entity.Facts;
import prepare.edt.orchestrator.usecase.ResponseDecoder.UndecodableResponseException;

/** Messages written from the RTDS 4.10.0 examples ECS17b, GCS13a and CCS06 (same structure and values, shorter header). */
class RealResponseDecoderTest {

    final RealResponseDecoder decoder = new RealResponseDecoder();

    static String wrap(String smetsData, String debugInfo) {
        return """
                <ra:GBCSResponse xmlns:ra="http://www.dccinterface.co.uk/ResponseAndAlert" schemaVersion="5.5">
                  <ra:Header/>
                  <ra:Body><ra:ResponseMessage>
                    <ra:SMETSData>%s</ra:SMETSData>%s
                  </ra:ResponseMessage></ra:Body>
                </ra:GBCSResponse>""".formatted(smetsData, debugInfo);
    }

    /** ECS17b_4.1.1_SINGLE_SUCCESS */
    static final String ECS17B_SINGLE = wrap("""
            <ra:ReadInstantaneousImportRegistersRsp MessageSuccess="true"><ra:Electricity>
              <ra:ActiveImportRegister><ra:Value>10</ra:Value><ra:ActiveEnergyUnit>Wh</ra:ActiveEnergyUnit></ra:ActiveImportRegister>
              <ra:ReactiveImportRegister><ra:Value>10</ra:Value><ra:ReactiveEnergyUnit>varh</ra:ReactiveEnergyUnit></ra:ReactiveImportRegister>
            </ra:Electricity></ra:ReadInstantaneousImportRegistersRsp>""", "");

    /** ECS17b_4.1.1_TWIN_SUCCESS */
    static final String ECS17B_TWIN = wrap("""
            <ra:ReadInstantaneousImportRegistersRsp MessageSuccess="true"><ra:Electricity>
              <ra:ActiveImportRegister><ra:Value>10</ra:Value><ra:ActiveEnergyUnit>Wh</ra:ActiveEnergyUnit></ra:ActiveImportRegister>
              <ra:ReactiveImportRegister><ra:Value>10</ra:Value><ra:ReactiveEnergyUnit>varh</ra:ReactiveEnergyUnit></ra:ReactiveImportRegister>
              <ra:SecondaryActiveImportRegister><ra:Value>10</ra:Value><ra:ActiveEnergyUnit>Wh</ra:ActiveEnergyUnit></ra:SecondaryActiveImportRegister>
            </ra:Electricity></ra:ReadInstantaneousImportRegistersRsp>""", "");

    /** ECS17b_4.1.1_ERROR */
    static final String ECS17B_ERROR = wrap("""
            <ra:ReadInstantaneousImportRegistersRsp MessageSuccess="false"><ra:Electricity/></ra:ReadInstantaneousImportRegistersRsp>""",
            """
            <ra:DebugInfo><ra:COSEMDebug><ra:COSEMResponse ResponseCode="1" Position="1">
              <ra:ActionResult>hardware-fault</ra:ActionResult></ra:COSEMResponse></ra:COSEMDebug></ra:DebugInfo>""");

    /** GCS13a_4.1.1_SUCCESS_RELIABLE_GSME */
    static final String GCS13A = wrap("""
            <ra:ReadInstantaneousImportRegistersRsp MessageSuccess="true"><ra:Gas>
              <ra:ConsumptionRegister><ra:Value>12.345</ra:Value><ra:Unit>m3</ra:Unit></ra:ConsumptionRegister>
            </ra:Gas></ra:ReadInstantaneousImportRegistersRsp>""", "");

    /** GCS13a_4.1.1_ERROR: a Unit but no Value; two failed attributes and a successful one in the debug information */
    static final String GCS13A_ERROR = wrap("""
            <ra:ReadInstantaneousImportRegistersRsp MessageSuccess="false"><ra:Gas>
              <ra:ConsumptionRegister><ra:Unit>m3</ra:Unit></ra:ConsumptionRegister>
            </ra:Gas></ra:ReadInstantaneousImportRegistersRsp>""", """
            <ra:DebugInfo><ra:ZIGBEEDebug><ra:ZIGBEEClusterResponse>
              <ra:ZIGBEEDataResponse><ra:Status ResponseCode="C0"><ra:ZCLStatus>HARDWARE_FAILURE</ra:ZCLStatus></ra:Status></ra:ZIGBEEDataResponse>
              <ra:ZIGBEEDataResponse><ra:Status ResponseCode="00"><ra:ZCLStatus>SUCCESS</ra:ZCLStatus></ra:Status></ra:ZIGBEEDataResponse>
              <ra:ZIGBEEDataResponse><ra:Status ResponseCode="C0"><ra:ZCLStatus>HARDWARE_FAILURE</ra:ZCLStatus></ra:Status></ra:ZIGBEEDataResponse>
            </ra:ZIGBEEClusterResponse></ra:ZIGBEEDebug></ra:DebugInfo>""");

    /** CCS06_8.9_SUCCESS */
    static final String CCS06 = wrap("""
            <ra:ReadDeviceLogRsp MessageSuccess="true"><ra:CHFDeviceLog>
              <ra:CHFDeviceLogEntry><ra:DeviceID>00-DB-12-34-56-78-90-A0</ra:DeviceID>
                <ra:LastCommunicationsDateTime>2014-12-31T23:50:00.00Z</ra:LastCommunicationsDateTime><ra:SubGHzLinkQuality>-50</ra:SubGHzLinkQuality></ra:CHFDeviceLogEntry>
              <ra:CHFDeviceLogEntry><ra:DeviceID>00-DB-12-34-56-78-90-A1</ra:DeviceID>
                <ra:LastCommunicationsDateTime>2014-12-31T23:40:00.00Z</ra:LastCommunicationsDateTime><ra:SubGHzLinkQuality>-54</ra:SubGHzLinkQuality></ra:CHFDeviceLogEntry>
            </ra:CHFDeviceLog></ra:ReadDeviceLogRsp>""", "");

    @Test
    void electricitySingleElement() {
        assertEquals(Map.of("MessageSuccess", "true",
                "ActiveImportRegister", "10", "ActiveImportRegister.Unit", "Wh",
                "ReactiveImportRegister", "10", "ReactiveImportRegister.Unit", "varh"),
                decoder.decode("4.1.1", ECS17B_SINGLE).values());
    }

    @Test
    void electricityTwinElementAddsTheSecondaryRegister() {
        Facts facts = decoder.decode("4.1.1", ECS17B_TWIN);
        assertEquals("10", facts.values().get("SecondaryActiveImportRegister"));
        assertEquals("Wh", facts.values().get("SecondaryActiveImportRegister.Unit"));
        assertEquals(7, facts.values().size());
    }

    @Test
    void gasMeter() {
        assertEquals(Map.of("MessageSuccess", "true", "ConsumptionRegister", "12.345", "ConsumptionRegister.Unit", "m3"),
                decoder.decode("4.1.1", GCS13A).values());
    }

    @Test
    void deviceLogHasOnePairOfFactsPerDevice() {
        Map<String, String> values = decoder.decode("8.9", CCS06).values();
        assertEquals(5, values.size());
        assertEquals("2014-12-31T23:50:00.00Z", values.get("CHFDeviceLog.00-DB-12-34-56-78-90-A0.LastCommunicationsDateTime"));
        assertEquals("-54", values.get("CHFDeviceLog.00-DB-12-34-56-78-90-A1.SubGHzLinkQuality"));
    }

    @Test
    void emptyDeviceLogHasOnlyMessageSuccess() {
        String empty = wrap("<ra:ReadDeviceLogRsp MessageSuccess=\"true\"><ra:CHFDeviceLog/></ra:ReadDeviceLogRsp>", "");
        assertEquals(Map.of("MessageSuccess", "true"), decoder.decode("8.9", empty).values());
    }

    @Test
    void electricityErrorRecordsTheFailureAndNoRegister() {
        assertEquals(Map.of("MessageSuccess", "false", "DebugSummary", "hardware-fault"),
                decoder.decode("4.1.1", ECS17B_ERROR).values());
    }

    @Test
    void gasErrorSummaryHasDistinctNonSuccessResultsAndNoFactWithoutValue() {
        Map<String, String> values = decoder.decode("4.1.1", GCS13A_ERROR).values();
        assertEquals(Map.of("MessageSuccess", "false", "DebugSummary", "HARDWARE_FAILURE"), values);
        assertFalse(values.containsKey("ConsumptionRegister.Unit"));
    }

    @Test
    void successHasNoDebugSummary() {
        assertFalse(decoder.decode("4.1.1", ECS17B_SINGLE).values().containsKey("DebugSummary"));
    }

    @Test
    void undecodableInputs() {
        assertThrows(UndecodableResponseException.class, () -> decoder.decode("4.1.1", "not xml"));
        assertThrows(UndecodableResponseException.class, () -> decoder.decode("6.15.1", ECS17B_SINGLE));
        assertThrows(UndecodableResponseException.class, () -> decoder.decode("8.9", ECS17B_SINGLE));   // no ReadDeviceLogRsp
        assertThrows(UndecodableResponseException.class, () -> decoder.decode("4.1.1", "<Response><Body/></Response>"));
        var e = assertThrows(UndecodableResponseException.class, () -> decoder.decode("4.1.1", "<a>"));
        assertTrue(e.getMessage().startsWith("not well-formed"));
    }
}
