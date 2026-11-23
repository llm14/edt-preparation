# Tasks

Test classes named below are the ones to add or update; run a module with `./gradlew :<module>:test` (the simulator under
`dcc-tools/`). The inline messages in tests are written from the RTDS examples named in each task and say so in a comment.

## 1. The real Service Request (Request side, independent of the gate)

- [x] 1.1 Add the partial schema `duis-signer/src/main/resources/schema/service-request.xsd` in the ServiceUserGateway namespace (Body is a choice of `ReadInstantaneousImportRegisters` and `ReadDeviceLog`, `schemaVersion` 5.5, comment saying it is not the DUIS schema) and delete `service-request-placeholder.xsd`; verify `XmlServiceRequestReaderTest` accepts the 4.1.1 (ECS17b) and 8.9 (CCS06) requests and rejects a wrong namespace, two Body elements and a missing header field
- [x] 1.2 Change `XmlServiceRequestReader` to take the Request ID and SRV from the XML, the originator from the part of the Request ID before the first colon, and the device type from an argument; verify `XmlServiceRequestReaderTest` (originator `90-B3-D5-1F-30-01-00-00`, SRV 4.1.1, device type from the argument) passes
- [x] 1.3 Add the `deviceType` argument to `SignActivity` in both copies (`duis-signer` and `orchestrator`, activity name `Sign` unchanged), `SignActivityImpl` and the use case; verify `SignActivityImplTest` and `SignServiceRequestTest` pass and `ServiceRequestWorkflowTest` still passes with the new stub
- [x] 1.4 In `duis-signer/src/main/resources/application.properties` and `PolicyConfig` replace the Other User entry `8.2=CHF` with `8.9=CHF`; verify `SigningPolicyTest` allows CHF for 8.9, refuses CHF for 4.1.1 and refuses 8.2, and a new `SchemaAllowlistConsistencyTest` fails when an SRV the schema accepts is on no allowlist (one direction: the placeholder `6.15.1` of the Registered Supplier Agent stays on the allowlist and has no body in the schema)
- [x] 1.5 Rewrite `RequestBuilderActivityImpl` to write the DUIS 5.5 request from an SRV table (4.1.1 to 4.1 and `ReadInstantaneousImportRegisters`, 8.9 to 8.9 and `ReadDeviceLog`, `CommandVariant` 1) and fail with a reason for an unknown SRV; verify `RequestBuilderActivityImplTest` (headers, empty bodies, no device type element, Request ID format, unknown SRV)
- [x] 1.6 Change `PlaceholderFlowWorkflowImpl` to send 8.9 to a CHF; verify `PlaceholderFlowWorkflowTest` (ESME and GSME send 4.1.1, CHF sends 8.9)
- [ ] 1.7 Update the fixtures that still hold the placeholder request (`JcaXmlSignerTest`, `KmsProviderTest`, `samples/sign-probe/signed.json`, `refused.json`, and their README) to the real request; verify `./gradlew :duis-signer:test` passes and the sign-probe README commands run as written on the cluster
- [x] 1.8 Make the DSP simulator's `DspMessages.read` read the real request (Request ID, SRV, and the device as the middle part of the Request ID, signature present), still answering with the placeholder synchronous response, Response and DCC Alert, using the Request ID as the `RequestId` element; verify a new `DspMessagesTest` (read from the 4.1.1 and 8.9 examples) and the existing `DeviceBehaviourTest` pass
- [ ] 1.9 Check the Request side on the local cluster: `deploy/edt.sh up`, upload `samples/manifests/one-device.csv`; verify the Batch ends with verdict `RESPONDS`, Temporal shows the four workflows `COMPLETED`, and `deploy/check-interactions.sh` and `deploy/check-log-shape.sh` pass
- [ ] 1.10 Document the Request side in README (the request is real DUIS 5.5, the schema is partial) and `HAPPY-PATH-MANUAL.md`; verify the documented commands run as written

## 2. Gate: what reaches the Response side

- [x] 2.1 Ask the lead what the DSP delivers to the Response Listener (the decoded MMC XML, or a DUIS Response that carries a GBCS payload and the Request ID), record the answer in `design.md` D0 and in the proposal's open question 4; verify `design.md` D0 states the answer. If the answer is the GBCS payload, stop and update this change (D4, D6 and D7, specs `dsp-response`) before group 3

## 3. Response decoding (after the gate)

- [x] 3.1 Add the 4.1.1 decoder for electricity and gas (per-SRV map, device type read from the `Electricity` or `Gas` child) and make it the `ResponseDecoder`; verify `RealResponseDecoderTest` with the ECS17b single and twin-element and GCS13a examples (Facts `ActiveImportRegister`, `ReactiveImportRegister`, `SecondaryActiveImportRegister`, `ConsumptionRegister`, each with `.Unit`)
- [x] 3.2 Add the 8.9 decoder (one `CHFDeviceLog.<deviceId>.LastCommunicationsDateTime` and `.SubGHzLinkQuality` per entry); verify `RealResponseDecoderTest` with the CCS06 example and an empty log
- [x] 3.3 Record `MessageSuccess` always and `DebugSummary` (distinct non-success COSEM and ZigBee result strings) when it is `false`, with no Fact for a value the Response lacks; verify `RealResponseDecoderTest` with the ECS17b error (`hardware-fault`) and GCS13a error (`HARDWARE_FAILURE`) examples
- [x] 3.4 Raise `UndecodableResponseException` for XML that is not well-formed, an SRV with no decoder, and a Response without its `...Rsp` element, delete `PlaceholderResponseDecoder` and its test, and update `ResponseDecoderActivityImplTest` and `ServiceRequestWorkflowTest` fixtures; verify both pass and the workflow ends `DECODE_FAILED` for each case
- [x] 3.5 Update the Facts in `ResultPublisherActivityImplTest` and `PlaceholderFlowWorkflowTest` to the new names; verify `./gradlew :orchestrator:test` passes, including the architecture test

## 4. The Response Listener (after the gate)

- [x] 4.1 Change `XmlDspMessageReader` to read a `GBCSResponse` with a `ResponseMessage` and rebuild the Request ID from `BusinessTargetID:BusinessOriginatorID:OriginatorCounter`, or from the supplementary fields when `SupplementaryRemotePartyID` is present; keep reading the placeholder `DCCAlert`; refuse a `DeviceAlertMessage`, a placeholder `Response` and an incomplete header as unreadable; verify `XmlDspMessageReaderTest` with the ECS17b (`...90-A0:1000`) and CCS06 (`...90-A2:1000`) headers, an 8F66 alert and a placeholder N12 alert
- [x] 4.2 Update the fixtures of `DspMessageResourceTest` and `DspSignatureVerifierTest` (both apps) to the real Response; verify `./gradlew :response-listener:test` and `:orchestrator:test` pass, and that a Response changed after signing still signals no workflow

## 5. The DSP simulator Response (after the gate)

- [ ] 5.1 Write the real Response in `DspMessages` for 4.1.1 on an electricity meter (default) and a gas meter, and for 8.9 with two log entries, with the header built from the request (device, originator, counter; for 8.9 the Access Control Broker as target and the supplementary fields); verify `DspMessagesTest` against the ECS17b, GCS13a and CCS06 shapes
- [ ] 5.2 Add the setting `EDT_GAS_DEVICES` (a list of device IDs that are gas meters), and make the tampered Response flip `MessageSuccess` after signing; verify `DspMessagesTest` and `DeviceBehaviourTest` (A0 to A7 behaviours unchanged)
- [ ] 5.3 Pass the setting in `dcc-tools/docker-compose.yml` and in `deploy/helm/dcc-tools` (value and README of the chart); verify `helm template deploy/helm/dcc-tools` renders `EDT_GAS_DEVICES`

## 6. Samples, documentation and the whole run

- [ ] 6.1 Add `samples/manifests/three-types.csv` (one ESME, one GSME listed in `EDT_GAS_DEVICES`, one CHF) and describe it in `samples/manifests/README.md`; verify a new `SampleManifestsTest` in the orchestrator parses every row of the file with `ManifestRow.parse` (three valid rows, types ESME, GSME and CHF)
- [ ] 6.2 Update README (the Response, the Facts, `EDT_GAS_DEVICES`) and `HAPPY-PATH-MANUAL.md` (Facts seen in Kafka); verify the documented commands run as written
- [ ] 6.3 Run the whole path on the cluster after `deploy/edt.sh down` and `up`: upload `three-types.csv`; verify one `FlowStep` per device with the Facts of D4, three `DeviceResult` with verdict `RESPONDS`, one `BatchResult` with `devices` 3, and that devices ending A1, A2, A3, A5, A6 and A7 still end as before (N12 and N13 failed, silent timed out, N53 then Response, tampered Response discarded, tampered synchronous response refused)
- [ ] 6.4 Run `./gradlew test` for all modules and `./gradlew test` from `dcc-tools/` (a separate Gradle build); verify green

## Workflow follow-up

- Review the plan and answer the open questions in the proposal, mainly question 4.
- Sync the new specs `service-request` and `dsp-response` and archive the change after the review requirements are satisfied.
