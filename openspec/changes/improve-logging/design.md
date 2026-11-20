# Design

## Context

Read from the repository and the model (2026-11-20), not assumed. See proposal.md for the motivation.

- **One engine.** All four apps log through the JBoss LogManager 3.2.2 and `quarkus-logging-json`. The 14 files that use JBoss
  Logging and the 5 workflow files that use SLF4J (through `slf4j-jboss-logmanager` 2.0.2) end in the same formatter, so the
  library choice changes nothing in the JSON. Fields reach the JSON only through the MDC, which `LogFields` fills. The bridge
  has no SLF4J key-value support (see the archived `structured-logging` design), so the MDC stays the way to add fields.
- **Today's loggers.** Class loggers, plus two named ones: `edt.audit` (DUIS Signer, one event per signing decision) and
  `edt.security` (Batch Ingest, Orchestrator, Response Listener, alerts about untrusted input).
- **Call sites.** 45 `LogFields.of` sites and 33 log calls. No log call passes a `Throwable`. `error.message` is set by hand in
  10 places and `error.type` in 1.
- **Boundaries in the code.** Every interface of the model has a place where the call is made: `SqsManifestEventConsumer`
  (SQS receive and delete), `S3ManifestVerifier` (S3 tag and object, then `signature.verify`, which is the only point where a KMS
  Verify call is made: the digest is built locally and sent to KMS in `engineVerify`), `TemporalBatchStarter`,
  `ManifestReaderActivityImpl` (S3 byte ranges), `DuisSenderActivityImpl.send` (HTTP POST), `ResultPublisherActivityImpl`
  (Kafka), `DspMessageResource.receive` (HTTP in), `TemporalWorkflowSignaller`, `SigningWiring` (certificate store), and the
  `EcsAuditLog` (the Signer's KMS Sign, through `JcaXmlSigner`).
- **Model.** The interface IDs used below are those of `architecture-model.md` (section Interfaces and the relations of each
  container). The inbound hop from the gateway has the ID `dcc.dspGatewayReqHttp`.
- **Measured.** A log line in the running cluster is about 900 bytes in the Orchestrator and 650 to 720 bytes in the other
  apps. The Orchestrator, DUIS Signer and Response Listener write about 5 lines per Service Request.

## Goals / Non-Goals

**Goals:**
- One INFO line for each boundary crossing, findable by one logger name and by the model's interface ID.
- The same Batch and Request IDs searchable across apps.
- A stack trace for a technical failure, never for input that an outsider can trigger at will.
- One logging API, kept that way by a test.

**Non-Goals:**
- Anything in the proposal's out of scope. Also: no interceptor on the AWS, Kafka or Temporal clients (decision 4), and no change
  to the Temporal activity or workflow signatures.

## Decisions

### 1. A dedicated logger, `edt.interaction`, and an explicit level in each `application.properties`
Each app sets `quarkus.log.category."edt.interaction".level=INFO`. The line is there to be found and changed, and
`LoggingConfigTest` asserts it. *Why:* DCC can turn the category down without a code change if the volume is too high, the log
agent can route on `log.logger`, and it follows the pattern of `edt.audit` and `edt.security`.
*Rejected:* each class's own logger (no single switch); sub-categories per boundary such as `edt.interaction.kms` (more
configuration than there is a reason for yet).

### 2. The same keys on every interaction line, tied to the model
`event.action`, `event.outcome` (`success` or `failure`), `edt.interface` (the model's interface ID), `network.direction`
(`outbound` for a call the container makes, `inbound` for a call it receives), and `event.duration` (nanoseconds, written as a
string like the other fields) for calls the container makes. Peers and identifiers are added per line (table below). Values
are strings. *Why:* ECS already names direction, outcome, duration and HTTP status; `edt.interface` makes each line traceable to
a box in the model. *Rejected:* a free-text description of the peer (not searchable); our own interface names (a second
vocabulary to keep in step with the model; see Open questions).

| # | Interface (model ID) | Container | Written in | `event.action` | State | Fields besides the common keys |
|---|---|---|---|---|---|---|
| 1 | `edt.batchIngestReqSqs` | Batch Ingest | `SqsManifestEventConsumer.handle` | `manifest-received` | moved, enriched | `edt.sqs_message_id`, `edt.manifest.*` (as today) |
| 2 | `edt.batchIngestReqSqs` | Batch Ingest | after `deleteMessage` | `sqs-message-deleted` | new | `edt.sqs_message_id`, `event.duration` |
| 3 | `edt.batchIngestReqS3` | Batch Ingest | `S3ManifestVerifier` (tag and object) | `manifest-read` | new | `edt.manifest.bucket/key/version_id`, bytes read, `event.duration` |
| 4 | `edt.batchIngestReqKms` | Batch Ingest | `S3ManifestVerifier` around `signature.verify` | `manifest-signature-checked` | new | `edt.signature_provider`, `event.duration`; `event.outcome` `failure` when invalid |
| 5 | `edt.batchIngestReqGrpc` | Batch Ingest | `TemporalBatchStarter.start` (the line moves here from the consumer) | `batch-started`, `batch-already-started` | moved, enriched | `edt.batch_id`, `edt.temporal.task_queue`, `event.duration` |
| 6 | `edt.orchestratorReqGrpc` | Orchestrator | `TemporalWorker` | `worker-started` | moved | as today |
| 7 | `edt.orchestratorReqGrpc` | Orchestrator | `BatchWorkflowImpl`, first run only | `batch-start-received` | new | `edt.batch_id` |
| 8 | `edt.orchestratorReqS3` | Orchestrator | `ManifestReaderActivityImpl` | `manifest-page-read` | moved, enriched | as today, `event.duration` |
| 9 | `edt.orchestratorReqHttp` | Orchestrator | `DuisSenderActivityImpl.send` | `service-request-sent` | new | `destination.address`, `http.response.status_code` (when answered), `http.request.body.bytes`, `edt.request_id`, `event.duration` |
| 10 | `edt.orchestratorReqGrpc` | Orchestrator | `ServiceRequestWorkflowImpl`, signal handler | `dsp-message-received` | new | `edt.request_id`, `edt.message_type`, `edt.alert_code` |
| 11 | `edt.orchestratorReqKafka` | Orchestrator | `ResultPublisherActivityImpl` | `result-published` | moved, enriched | as today, `event.duration` |
| 12 | `edt.orchestratorReqFile` | Orchestrator | `DspCertificateWiring` | `dsp-certificate-loaded` | moved | as today |
| 13 | `edt.duisSignerReqGrpc` | DUIS Signer | `TemporalWorker` | `worker-started` | moved | as today |
| 14 | `edt.duisSignerReqKms` | DUIS Signer | `EcsAuditLog` (stays on `edt.audit`) | `sign-service-request` | enriched | `edt.interface`, `event.duration` of the signing |
| 15 | `edt.duisSignerReqFile` | DUIS Signer | `SigningWiring.loadKeyStore` | `certificate-store-loaded` | new | `edt.certificate_store` |
| 16 | `dcc.dspGatewayReqHttp` (in) | Response Listener | `DspMessageResource.receive` | `dsp-message-signalled`, `dsp-message-orphan` | moved, enriched | `network.direction` `inbound`, `event.duration` of handling and signalling, fields as today |
| 17 | `edt.responseListenerReqFile` | Response Listener | `DspCertificateWiring` | `dsp-certificate-loaded` | moved | as today |

The outbound Temporal signal of the Response Listener (`edt.responseListenerReqGrpc`) is covered by row 16: one line for the
handled message, which includes the signal. The Temporal client of Batch Ingest and Response Listener writes no line when it is
created: creating the client is not a crossing, and the first real crossing (rows 5 and 16) is logged.

### 3. The audit and security loggers stay as they are
`edt.audit` and `edt.security` are not moved. The audit line (row 14) is enriched with the duration of the signing and the
interface ID. *Why:* an audit event or a security alert must never disappear because someone turned `edt.interaction` down; and
a second line per Service Request for the KMS Sign would double the Signer's volume. The duration includes the local XML work
around the KMS call (it is measured around the signing use case), so it is "time to sign", not "KMS latency".
*Rejected:* a separate KMS interaction line (volume); moving the audit line (see above).

### 4. Lines are written where the call is made, not in the JCA providers and not by client interceptors
Rows 3 and 4 both come from `S3ManifestVerifier`: it times the S3 reads, then times `signature.verify` (the one point where the
digest goes to KMS). With the software provider used in tests there is no KMS call; the line is still written, and
`edt.signature_provider` says which provider ran. *Why:* the call sites know the identifiers (Batch ID, Request ID, bucket and
key) and the outcome in business terms. *Rejected:* logging inside `KmsSignatureSpi` and `KmsVerifySignatureSpi` (a JCA engine
should stay a plain engine, and it knows no identifiers); AWS SDK execution interceptors (one place for SQS, S3 and KMS, but no
identifiers, one line for every call including each empty long poll and each tag read, and they would not cover HTTP, Kafka or
Temporal); a proxy around every client (the same problem with more code).

### 5. A small `Interaction` helper beside `LogFields`
It sets the common keys (`event.action`, `edt.interface`, `network.direction`), measures the duration, and sets
`event.outcome`; it is used in the same `try` block as `LogFields`. Four copies, as `LogFields` has (no shared module, per the
build-layout decision). *Why:* 17 call sites would otherwise repeat the same four keys and the same `System.nanoTime`
arithmetic. *Rejected:* repeating the keys at each site (typos in keys are exactly what a search then misses).

### 6. Workflow lines use the replay-aware logger under the same name
Rows 7 and 10 use `Workflow.getLogger("edt.interaction")`, around the log call only, as decision 4 of the archived
`structured-logging` design says. `batch-start-received` is written only when the workflow has no saved progress (the first
run, not a continue-as-new). `dsp-message-received` is written in the signal handler; Temporal runs it once per signal and the
replay-aware logger stays silent while replaying.

### 7. A failed call is one line, once per attempt
`service-request-sent` is written with `event.outcome` `failure` before the exception propagates, once for each attempt. It
carries no stack: the Temporal worker already logs a failed activity with its stack (to be confirmed by the test in the tasks).
*Rejected:* logging the exception here as well (two stacks for one failure).

### 8. Exceptions: technical failures pass the exception, expected rejections do not
Three lines pass the `Throwable` and stop setting `error.message` and `error.type` by hand, so the formatter writes
`error.type`, `error.message` and `error.stack_trace`: `manifest-ingest-failed` and `manifest-poll-failed` (Batch Ingest) and
`dsp-message-not-delivered` (Response Listener). Seven lines keep `error.message` as a field and carry no stack:
`manifest-event-unreadable`, `manifest-signature-invalid`, `manifest-row-invalid`, `response-not-decodable`,
`dsp-signature-invalid` (twice: Response Listener and the Orchestrator's synchronous response), `dsp-message-unreadable`. *Why:*
these seven are reached by outside input, so an outsider could make us write a stack per message, and an exception's message can
echo part of that input; the Response Listener deliberately logs nothing from an untrusted message. *Rejected:* a stack trace
on every error line (log flooding from untrusted input); keeping `error.message` by hand everywhere (no stack for technical
failures, the gap this decision closes).

### 9. One API: SLF4J
All 14 JBoss Logging files use `org.slf4j.LoggerFactory` (class loggers and the named ones, `edt.audit`, `edt.security`,
`edt.interaction`). `LogFields` writes `org.slf4j.MDC`, which the bridge maps to the same MDC the formatter reads (the archived
spike already showed Temporal's SLF4J MDC keys and ours in one map). Workflows keep `Workflow.getLogger`. Each app's
`ArchitectureTest` gets a rule that fails if any class depends on `org.jboss.logging`. This is done first, so the new lines
are written once, in the final API. *Why:* one API to learn and review, the one Temporal's SDK uses, and a rule that keeps it
so. *Rejected:* leaving two APIs (nothing enforces which to use); Logback or Log4j2 (Quarkus logs through the JBoss
LogManager; a second engine would not reach the JSON formatter).

### 10. One Batch ID name: `edt.batch_id`
Batch Ingest's `edt.batch_workflow_id` becomes `edt.batch_id`; the value is the same (`ManifestRef.batchWorkflowId()`, the
workflow ID of the Batch). The Orchestrator already uses `edt.batch_id`. *Rejected:* keeping both (a search needs both names);
`edt.batch_workflow_id` everywhere (the Orchestrator and its Kafka events already say Batch ID).

### 11. Checks
- `LoggingConfigTest` (per app) also asserts the `edt.interaction` category line.
- A per-app test captures the log lines (the `LogCapture` technique of the archived change) and asserts the new and enriched lines.
- `deploy/check-log-shape.sh` requires `event.action`, `event.outcome`, `edt.interface` and `network.direction` on every line whose
  `log.logger` is `edt.interaction`.
- A new `deploy/check-interactions.sh` lists, per container, the `edt.interface` values the model says a one-device run uses
  (table above) and fails for any that has no line in that container's log.
- The exception rule is checked on the cluster by making a technical failure happen (Floci stopped, so the poll fails) and
  reading the line.

## Risks / Trade-offs

- **Volume.** Rows 9 and 10 add about 2 lines to the 5 each Service Request writes today (about 40% more); the rest happen once
  per Manifest, Page or start-up. At 650 to 900 bytes a line and, as a guess, 10,000 devices with 8 Service Requests each, that
  is about 80,000 Service Requests: about 320 MB per Batch now and about 450 MB after. The device and step counts are guesses
  (the Flow content is not final). The mitigation is decision 1: one setting turns the category down.
- **Consumers keyed on `log.logger` or on `edt.batch_workflow_id`** stop matching the moved lines and the renamed field.
  Nothing in this repository reads either; DCC's side is an open question.
- **A stack trace every poll interval.** While the queue is unreachable, `manifest-poll-failed` repeats after each 5-second
  pause and each line now holds a stack of several KB (a few MB an hour). Accepted: it is a technical failure someone must see.
- **`Request ID` from the workflow ID.** Row 9 runs in an activity that receives only the signed XML. The Request ID is taken
  from the workflow ID (`service-request-<Request ID>`, the rule `ServiceRequestWorkflowImpl` uses the other way round); a test
  proves the two agree. *Rejected:* adding the Request ID to the activity's input (a change to the Temporal contract).
- **Migration to SLF4J can change a message.** SLF4J treats `{}` as a placeholder and JBoss Logging does not; no message in the
  code contains one, and the tests and the cluster check cover the rest.
- **Moved lines lose their class name** as `log.logger`; the class stays in `process.thread.name` and the message, not in a
  field. Accepted.

## Migration Plan

No data migration. Deploy with `deploy/edt.sh up` (or Compose), run the one-device sample, then `deploy/check-log-shape.sh` and
`deploy/check-interactions.sh`. Rollback is per decision: set the `edt.interaction` level to WARN to silence the new lines
without a deploy of code; reverting the commit restores the old field name and logger names.

## Open Questions

- **Volume and routing at DCC** (`dcc.logAgent`, `dcc.cloudWatch`): can CloudWatch Logs take the extra INFO lines, and should
  the agent route `edt.interaction`? [CONFIRM] with DCC.
- **`edt.interface` values** copy the model's IDs (`edt.orchestratorReqHttp`, ...). If those IDs change in the model the logs
  change with them. Keep the coupling, or give the field names of our own? Affects every container element in the model.
- **Stack traces and untrusted data.** Each of the three lines in decision 8 passes an exception from our own technical
  failures, which does not carry outside input; a reviewer who knows the DCC adapter code should confirm that for
  `dsp-message-not-delivered` (`dcc.dspGateway` to `edt.responseListener`).
- **Temporal's own failed-activity log** (decision 7) must be confirmed to carry the stack; if not, row 9 needs it.
- **Sub-categories** per boundary, if one boundary proves noisy.
- **`temporal.workflow_id` and Temporal's CamelCase keys** remain open from `structured-logging`.
