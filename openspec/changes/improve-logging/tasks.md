# Tasks

Row numbers refer to the table in design.md, decision 2. The order matters: group 1 first, so the new lines are written once in
the final logging API.

## 1. One logging API: SLF4J (design decision 9)

- [x] 1.1 In Batch Ingest, DUIS Signer and Response Listener, copy the `LogCapture` test helper from the Orchestrator into the app's test sources, and give all four copies a `Throwable thrown` on each captured line (`ExtLogRecord.getThrown()`). In all four apps add `LogFieldsTest`: `LogFields.of` puts its keys in the MDC of a captured line, and removes them when the block closes. Verify: `./gradlew test` passes in the four apps, against the JBoss MDC they use today (this test is the safety net for the migration).
- [x] 1.2 Orchestrator: add an ArchUnit rule in `ArchitectureTest` that no class depends on `org.jboss.logging`; run it and watch it fail; then move `ManifestReaderActivityImpl`, `ResponseDecoderActivityImpl`, `ResultPublisherActivityImpl`, `DuisSenderActivityImpl`, `TemporalWorker` and `DspCertificateWiring` to `org.slf4j.LoggerFactory` (keeping the logger name `edt.security`) and `LogFields` to `org.slf4j.MDC`. Verify: `./gradlew :orchestrator:test` passes, including the rule and `LogFieldsTest`, and every existing workflow log test.
- [x] 1.3 Batch Ingest: the same (`SqsManifestEventConsumer` with its `LOG` and `SECURITY` loggers, `IngestWiring`, `ManifestEventPolling`, `LogFields`). Verify: `./gradlew :batch-ingest:test` passes, and the new rule failed before the change.
- [x] 1.4 DUIS Signer: the same (`EcsAuditLog` keeps the logger name `edt.audit`, `SigningWiring`, `TemporalWorker`, `LogFields`). Verify: `./gradlew :duis-signer:test` passes, and the new rule failed before the change.
- [x] 1.5 Response Listener: the same (`DspMessageResource` with `LOG` and `SECURITY`, `DspCertificateWiring`, `LogFields`). Verify: `./gradlew :response-listener:test` passes, and the new rule failed before the change.

## 2. Foundations

- [x] 2.1 In the four apps, add `quarkus.log.category."edt.interaction".level=INFO` to `application.properties`, and assert it in `LoggingConfigTest` first. Verify: `./gradlew test` fails on the new assertion before the property and passes after, in each app.
- [x] 2.2 Add the `Interaction` helper next to `LogFields` in each app (common keys `event.action`, `edt.interface`, `network.direction`; `event.outcome`; a duration measured with `System.nanoTime`; fields removed on close) with `InteractionTest`. Verify: `InteractionTest` passes in each app: the keys are present, the duration is not negative, `failure` can be set, nothing is left in the MDC after the block.
- [x] 2.3 Batch Ingest: rename `edt.batch_workflow_id` to `edt.batch_id` in `SqsManifestEventConsumer`, and in `IngestManifestTest` where the old name is used. Verify: `SqsManifestEventConsumerTest` asserts the "Batch started" line has `edt.batch_id` and none has `edt.batch_workflow_id`; `./gradlew :batch-ingest:test` passes.

## 3. Batch Ingest lines (rows 1 to 5)

- [ ] 3.1 `SqsManifestEventConsumer`: move "Manifest received" to `edt.interaction` with the common keys (row 1); write `sqs-message-deleted` after the delete (row 2); write nothing for an empty poll. Verify: `SqsManifestEventConsumerTest` asserts both lines and their keys, and that `pollOnce` with no message writes no `edt.interaction` line.
- [ ] 3.2 `S3ManifestVerifier`: write `manifest-read` after the S3 reads (row 3) and `manifest-signature-checked` around `signature.verify` (row 4), `failure` when the signature is invalid or missing. Verify: `S3ManifestVerifierTest` asserts both lines for a valid and for an invalid Manifest, and that neither contains the signature or any Manifest content.
- [ ] 3.3 `TemporalBatchStarter`: write `batch-started` and `batch-already-started` here with the task queue and the duration (row 5), and remove them from the consumer. Verify: `TemporalBatchStarterTest` asserts one line for a new Batch and one for a duplicate, each with `edt.batch_id`; `SqsManifestEventConsumerTest` shows no second line for the same event.

## 4. Orchestrator lines (rows 6 to 12)

- [ ] 4.1 Move to `edt.interaction` with the common keys: `worker-started` (row 6, `TemporalWorker`), `manifest-page-read` with its duration (row 8), `result-published` with its duration (row 11), `dsp-certificate-loaded` (row 12). Verify: `ResultPublisherActivityImplTest` and a new `ManifestReaderActivityImplTest` assert the keys and a non-negative duration; the wiring lines are proved by `deploy/check-interactions.sh` in group 8.
- [ ] 4.2 `BatchWorkflowImpl`: write `batch-start-received` on the first run only (row 7). Verify: in `BatchLogFieldsTest`, a Batch that continues as new twice (three runs) has exactly one `batch-start-received` line, with `edt.batch_id`.
- [ ] 4.3 `DuisSenderActivityImpl.send`: write `service-request-sent` (row 9), once for each attempt, with `failure` and no stack when the post fails or the gateway answers other than 200; the Request ID from the workflow ID. Verify: `DuisSenderActivityImplTest` (with `TestDsp`) asserts the success line (status code, body size, duration, Request ID, no XML), the failure line for a refused connection and for HTTP 500, and that the Request ID taken from the workflow ID equals the one `RequestId.workflowId` was built from.
- [ ] 4.4 `ServiceRequestWorkflowImpl`: write `dsp-message-received` in the signal handler (row 10) on the replay-aware logger named `edt.interaction`. Verify: `ServiceRequestWorkflowTest` asserts one line per signal with the Request ID, message type and alert code; a `WorkflowReplayer` test shows the workflow's logger writes 0 lines on replay.

## 5. DUIS Signer lines (rows 13 to 15)

- [ ] 5.1 Enrich the audit line (row 14): add the duration of the signing to `AuditEvent` (measured in `SignServiceRequest` with `System.nanoTime`, no framework) and write `event.duration` and `edt.interface` in `EcsAuditLog`, which stays on `edt.audit`. Verify: a use case test shows a non-negative duration in the recorded event; `EcsAuditLog` test asserts `event.duration`, `edt.interface` and `log.logger` `edt.audit`.
- [ ] 5.2 Move `worker-started` to `edt.interaction` (row 13) and write `certificate-store-loaded` in `SigningWiring.loadKeyStore` (row 15), without the store's password or content. Verify: `./gradlew :duis-signer:test` passes; both lines are proved by `deploy/check-interactions.sh` in group 8.

## 6. Response Listener lines (rows 16 and 17)

- [ ] 6.1 `DspMessageResource.receive`: move `dsp-message-signalled` and `dsp-message-orphan` to `edt.interaction`, with `network.direction` `inbound`, `edt.interface` `dcc.dspGatewayReqHttp` and the duration of handling and signalling (row 16). Verify: `DspMessageResourceTest` asserts the keys on both lines, the Request ID and message type, and that no part of the XML is in the line.
- [ ] 6.2 Move `dsp-certificate-loaded` to `edt.interaction` (row 17). Verify: `./gradlew :response-listener:test` passes; the line is proved by `deploy/check-interactions.sh` in group 8.

## 7. Exceptions (design decision 8)

- [ ] 7.1 Batch Ingest: `manifest-ingest-failed` and `manifest-poll-failed` pass the exception and stop setting `error.message` and `error.type` as fields. Verify: `SqsManifestEventConsumerTest` and a new `ManifestEventPollingTest` assert the captured line has a `thrown` exception and no `error.*` field; the expected-rejection lines (`manifest-event-unreadable`, `manifest-signature-invalid`) still have `error.message` and no `thrown`.
- [ ] 7.2 Response Listener: `dsp-message-not-delivered` passes the exception. Verify: `DspMessageResourceTest` with a failing signaller asserts `thrown` and no `error.*` field; for an invalid DSP signature and an unreadable message it asserts `error.message` and no `thrown`.
- [ ] 7.3 Orchestrator: confirm the expected-rejection lines keep `error.message` and no exception (`manifest-row-invalid`, `response-not-decodable`, `dsp-signature-invalid`). Verify: the existing tests of those activities assert `thrown` is null and `error.message` is set.

## 8. Checks and documentation

- [ ] 8.1 Extend `deploy/check-log-shape.sh`: every line whose `log.logger` is `edt.interaction` needs `event.action`, `event.outcome`, `edt.interface` and `network.direction`; add `EDT_LOG_FIXTURE_DIR` so the script can read `<app>.log` files instead of the cluster. Verify: with a fixture holding one interaction line without `edt.interface` the script exits 1 and names the key; with a complete line it exits 0.
- [ ] 8.2 Write `deploy/check-interactions.sh`: per container, the `edt.interface` values a one-device run uses (design table) must each appear on an `edt.interaction` line; it accepts the same fixture directory. Verify: with a fixture missing one interface it exits 1 and names the container and interface; with all of them it exits 0.
- [ ] 8.3 Update README Step 4 (the interaction logger, its keys, how to turn it down, the rule on exceptions, one logging API) and `HAPPY-PATH-MANUAL.md` (the lines named there that moved). Verify: the examples in Step 4 match real lines seen in group 9.

## 9. Check on the cluster

- [ ] 9.1 Build and install (`deploy/edt.sh up`), run the one-device sample Manifest. Verify: `deploy/check-log-shape.sh` and `deploy/check-interactions.sh` exit 0; the real lines for rows 9 and 10 have the keys of the spec and no XML.
- [ ] 9.2 Technical failure: scale Floci to 0 until `manifest-poll-failed` appears, then scale it back. Verify: that line has `error.type`, `error.message` and `error.stack_trace` once each, and Batch Ingest polls again afterwards.
- [ ] 9.3 Failed send: stop the DSP simulator, upload a one-device Manifest, restore the simulator. Verify: `service-request-sent` with `failure` appears once per attempt, and Temporal's own failed-activity log carries the stack (design decision 7). **If it does not, stop and ask before changing row 9.**
- [ ] 9.4 Replay: upload a Manifest for `...-A3`, restart the Orchestrator during the wait, let it finish. Verify: each workflow and interaction line appears once for its real event (no repeat from the replay), `batch-start-received` once, and `deploy/check-log-shape.sh` still exits 0.

## Workflow follow-up

- Ask DCC about volume and routing of `edt.interaction`, about top-level fields (still [CONFIRM] from `structured-logging`), and whether the model's interface IDs are the values it wants in `edt.interface`; update the [CONFIRM] in the spec when answered.
- Archive the change after review, so the added and modified requirements reach `specs/structured-logging/`.
