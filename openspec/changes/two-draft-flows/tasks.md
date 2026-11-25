# Tasks

Test classes named below are the ones to add or update; run a module with `./gradlew :<module>:test` (the simulator under
`dcc-tools/`). Inline messages in tests are written from the RTDS examples named in each task and say so in a comment.
On Windows set `JAVA_HOME` to the full JDK (`C:\Program Files\Java\jdk-25.0.5.1`) first.

## 1. Request side of the three new SRVs

- [x] 1.1 Add the three request bodies `ReadDeviceConfigurationPaymentMode`, `ReadCHFSubGHzConfiguration` and `ReadFirmwareVersion` to the partial schema `duis-signer/src/main/resources/schema/service-request.xsd` (keep the "not the DUIS schema" label); verify `XmlServiceRequestReaderTest` accepts the 6.2.9 (ECS26j), 6.30 (DBCH03) and 11.2 (ECS52) requests and still rejects two Body elements
- [x] 1.2 Add the Other User allowlist entries `6.2.9=ESME,GSME`, `6.30=CHF` and `11.2=ESME,GSME,CHF` in `duis-signer/src/main/resources/application.properties` (and `PolicyConfig` if it lists SRVs); verify `SigningPolicyTest` allows each pair, refuses 6.30 for an ESME and 6.2.9 for a CHF, and `SchemaAllowlistConsistencyTest` still passes
- [x] 1.3 Add three rows to the SRV table of `RequestBuilderActivityImpl` (6.2.9 to service reference 6.2, 6.30 and 11.2 to themselves, with their body elements); verify `RequestBuilderActivityImplTest` for the three headers and empty bodies, and that SRV 4.1.1 and 8.9 and an unknown SRV behave as before
- [x] 1.4 Verify the request side end to end in `./gradlew :duis-signer:test :orchestrator:test` (including the architecture test), and document the five SRVs in the README table of what the builder and signer accept

## 2. Responses of the three new SRVs

- [x] 2.1 Add the 6.2.9 and 11.2 readers to `RealResponseDecoder` (Facts `PaymentMode`, `FirmwareVersion`, plus `MessageSuccess`); verify `RealResponseDecoderTest` with the ECS26j (6.2.9) and ECS52 (11.2) success examples
- [x] 2.2 Add the 6.30 reader (`MessageSuccess` and every direct scalar child of `ReadCHFSubGHzConfigurationRsp` by element name, no channel list Facts); verify `RealResponseDecoderTest` with the DBCH03 success example (for example `MaximumSubGHzChannelChangesPerWeek` 4, no Fact named after a channel)
- [x] 2.3 Check that `MessageSuccess="false"` gives `MessageSuccess` and `DebugSummary` and no value Facts for the three new SRVs, and that an SRV with no reader still raises `UndecodableResponseException`; verify `RealResponseDecoderTest` with the ECS26j, ECS52 and DBCH03 error examples
- [x] 2.4 Write the three new Responses in `dcc-tools/dsp-simulator` `DspMessages.response` (header built from the request as for the other SRVs, success values from the RTDS examples: `Prepayment`, `0123ABCD`, the DBCH03 thresholds); verify `DspMessagesTest` for each SRV and that `DeviceBehaviourTest` (A0 to A7) is unchanged
- [x] 2.5 Verify the Orchestrator accepts the simulator's three Responses: a `ServiceRequestWorkflowTest` case per SRV that signs, sends and decodes with the simulator's message as fixture (RESPONDED and the expected Facts)

## 3. The Flow Catalogue

- [x] 3.1 Add the entity records `Flow(name, steps)` and `Step(srv, deviceTypes)` in `orchestrator/entity` and the two draft Flows `hub-triage` (CHF: 8.9, 6.30) and `meter-triage` (ESME, GSME: 6.2.9, 11.2); verify a `FlowCatalogueTest` for the steps, their order and device types, and that the architecture test still passes (the entity layer imports nothing outward)
- [x] 3.2 Add the `FlowCatalogueActivity` (a Temporal activity, model element `edt.orchestrator.flowCatalogue`) whose `lookup(flow)` returns the Flow's steps or a not-found result, and register it in `TemporalWorker`; verify `FlowCatalogueActivityImplTest` for a known Flow, an unknown Flow and a case-sensitive name
- [x] 3.3 Document the catalogue (where a Flow is defined and how to add one) in the README section on the Flow; verify the README names the two Flows and the draft status, and cites `samples/draft_flows.md`

## 4. The Flow workflow runs the steps

- [x] 4.1 Replace `PlaceholderFlowWorkflowImpl` with a Flow workflow (same workflow type name `Flow`, same `FlowInput`) that looks the Flow up, refuses an unknown Flow or a device type the first step does not accept (Verdict `NOT_DIAGNOSED`, no request, log event `flow-refused`, no failed workflow); verify `FlowWorkflowTest` for an unknown Flow and an ESME on `hub-triage`
- [x] 4.2 Run the steps one after the other, one Service Request child each, with Request ID counter `max(Workflow.currentTimeMillis(), previous + 1)`, stopping at the first outcome that is not `RESPONDED`; verify `FlowWorkflowTest` (two steps answered in order, first step times out and no second request is sent, `MessageSuccess="false"` still continues, two Request IDs differ and the second counter is greater)
- [x] 4.3 Decide the Verdict over the steps (any `RESPONDED` is `RESPONDS`; else first step `TIMED_OUT` or `FAILED_ALERT` is `NO_RESPONSE`; else `NOT_DIAGNOSED`) and publish one `FlowStep` per step (event ID `<flow workflow id>/step-N`) and one `DeviceResult`; verify `FlowWorkflowTest` for the four Verdict scenarios of the spec and for the published events and their IDs
- [x] 4.4 Register the new Flow workflow in `TemporalWorker` in place of the placeholder and delete `PlaceholderFlowWorkflowImpl` and its test; verify `./gradlew :orchestrator:test` passes (including the architecture test) and `grep -rn PlaceholderFlow` finds only history in archived OpenSpec files
- [x] 4.5 Update the README and `HAPPY-PATH-MANUAL.md` (Flow row, step table, Facts table with the three new SRVs, the Verdict rule over steps, two `FlowStep` events per device); verify every documented command runs as written

## 5. Samples and cluster check

- [ ] 5.1 Rewrite the sample Manifests so each row names its Flow (`meter-triage` for ESME and GSME rows, `hub-triage` for CHF rows) and update `samples/manifests/README.md`; verify `SampleManifestsTest` (each file's purpose, A0 to A7, no device in two files, every `flow` is in the catalogue)
- [ ] 5.2 Check the draft Flows on the local cluster: `deploy/edt.sh up`, upload `one-device.csv`, `device-types.csv` and `device-behaviours.csv`; verify Temporal shows the workflows `COMPLETED`, Kafka holds two `FlowStep` events for each responding device and one for each silent one, and `device-behaviours.csv` ends with `devices` 8, `pages` 5 and the Verdicts of `samples/manifests/README.md` (`RESPONDS` 2, `NO_RESPONSE` 4, `NOT_DIAGNOSED` 2)
- [ ] 5.3 Run `deploy/check-interactions.sh` and `deploy/check-log-shape.sh`; verify both pass and the log events `flow-refused` and `flow-ended` carry the Flow name

## Workflow follow-up

- Update the learning pages in `edt-learning` (`edt-process.html`, section 2b and the Flow description) once the Flows run.
- Resolve the proposal's open questions with the lead and DCC before the Flows are treated as more than drafts.
- Archive the change after review.
