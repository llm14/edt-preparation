# Proposal

## Why

When a device "does not respond" the first question is where the Service Request stopped: never sent, sent and unanswered, or
answered and lost. Today the logs cannot say. Reading the code against the model's interfaces, 6 of the 17 places where an EDT
container calls or is called by another system write no INFO line when the call succeeds, and a seventh (the KMS Sign) has
only the audit line. The six are the SQS delete, the S3 and KMS reads of Batch Ingest, the Orchestrator's POST to the DSP HTTPS
Gateway, and the Orchestrator receiving a Response or a Batch start. Three smaller problems make the lines that do exist harder
to use:

1. **One Batch, two field names.** Batch Ingest logs the Batch ID as `edt.batch_workflow_id`, the Orchestrator as `edt.batch_id`
   (introduced by `structured-logging`). One search cannot follow a Batch across the two apps.
2. **No stack traces.** No log call passes an exception, so the JSON never has `error.stack_trace`; the code sets
   `error.message` by hand in 10 places and `error.type` in 1. A technical failure in the cluster leaves no stack.
3. **Two logging APIs.** 14 files log through JBoss Logging and 5 (the workflows) through SLF4J. The split has a reason
   (`Workflow.getLogger` is SLF4J and silent on replay) but nothing states or enforces it.

## What Changes

- **A line for every boundary crossing**, at INFO, on a dedicated logger named `edt.interaction`, written by the side that makes
  or receives the call. Each line carries `event.action`, `event.outcome`, `edt.interface` (the interface's ID in the model),
  `network.direction`, and `event.duration` for calls the container makes. Where a line already exists it is enriched and moved
  to this logger; where none exists it is added. The inventory is in design.md.
- **The level of `edt.interaction` can be set on its own** in each app's `application.properties`, so DCC can turn it down
  without a code change. The audit (`edt.audit`) and security (`edt.security`) loggers are not touched.
- **No content in these lines:** identifiers, status, size and duration only; never a Service Request or Response body, a
  signature, a key or certificate content.
- **One name for the Batch ID**, `edt.batch_id`, in all four apps.
- **Unexpected technical failures log their exception** (the formatter then writes `error.type`, `error.message` and
  `error.stack_trace`). Expected rejections of input from outside (invalid DSP signature, unreadable message, invalid Manifest
  row, undecodable Response) log a reason and no stack trace.
- **One logging API, SLF4J,** in all 14 files that use JBoss Logging today, with an architecture test that fails if
  `org.jboss.logging` is used again. `LogFields` keeps writing the MDC.
- Checks: `deploy/check-log-shape.sh` also requires the interaction keys on `edt.interaction` lines, and a new
  `deploy/check-interactions.sh` fails if a boundary that a one-device run uses has no line.
- Update README Step 4 and `HAPPY-PATH-MANUAL.md`.
- **BREAKING for anything that reads the logs by field or logger name:** `edt.batch_workflow_id` becomes `edt.batch_id`; the
  lines that move (for example "Manifest received", "Published to Kafka", "DSP message delivered ...") change `log.logger`
  from a class name to `edt.interaction`. Nothing in this repository reads either; DCC's side must be checked.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `structured-logging`: adds requirements for the interaction lines (one per boundary crossing, on `edt.interaction`, no
  content, level adjustable on its own), for one Batch ID name, and for logging the exception of a technical failure; and
  changes the requirement "A key appears at most once in a line" so that `error.message` and `error.type` come from the
  exception whenever a line has one.

## Impact

- Code, all four apps: `application.properties` (one line each), 14 files that move to SLF4J, the four `LogFields` copies, a
  small `Interaction` helper next to each, and the adapters and wiring classes that cross a boundary: `SqsManifestEventConsumer`,
  `S3ManifestVerifier`, `TemporalBatchStarter`, `ManifestEventPolling` (Batch Ingest);
  `BatchWorkflowImpl`, `ServiceRequestWorkflowImpl`, `DuisSenderActivityImpl`, `ManifestReaderActivityImpl`,
  `ResultPublisherActivityImpl`, `ResponseDecoderActivityImpl`, `TemporalWorker` (Orchestrator); `EcsAuditLog`,
  `SigningWiring`, `TemporalWorker` (DUIS Signer); `DspMessageResource`, `DspCertificateWiring` (Response Listener).
- Tests: a `LoggingConfigTest` check per app, a log-capture test helper per app, a test per new or enriched line, an
  ArchUnit rule per app.
- Scripts and docs: `deploy/check-log-shape.sh`, a new `deploy/check-interactions.sh`, README Step 4, `HAPPY-PATH-MANUAL.md`.
- Volume: about 40% more lines per Service Request than today (measured line size 650 to 900 bytes); see design.md, Risks.
- Model fit: no new container or relation. Each line is tied to an interface of the model through `edt.interface`; the DCC Log
  Agent (`dcc.logAgent`) reads the result into CloudWatch Logs (`dcc.cloudWatch`).
- Runs unchanged on Docker Compose and on the local cluster.

## Out of scope

- Tracing (`trace.id`), metrics, log retention, CloudWatch or log-agent configuration, anything on the DCC side.
- Renaming Temporal's own context keys (`WorkflowId`, `RunId`, ...) and deciding about `temporal.workflow_id`.
- A written vocabulary of every `event.action` and `edt.*` field outside the interaction lines.
- Logging payloads (Service Request or Response XML, Manifest rows, signatures) at any level.
- A line for an empty SQS poll, for each Temporal task poll, or for creating a Temporal client (it is not a crossing until the
  first call); the EDT is a Kafka producer only, so there is nothing to log on the consuming side.
- Sub-categories such as `edt.interaction.kms`, a shared logging module, and the DUIS Signer's audit lines moving off
  `edt.audit`.
- Changing the log level of any other logger.

## Assumptions made (confirm or correct)

1. Lines that report a model interface move to `edt.interaction`. Lifecycle lines ("... started", "... configured", "Polling
   the Manifest Events queue"), the audit and the security lines stay where they are.
2. `edt.interface` holds the interface ID from the model (for example `edt.orchestratorReqHttp`); the inbound hop from the
   gateway uses `dcc.dspGatewayReqHttp`.
3. `event.duration` is written the ECS way, in nanoseconds, as a string like the other fields.
4. The DUIS Signer's audit line stays on `edt.audit`, so turning `edt.interaction` down never silences an audit event. It is
   enriched with the KMS Sign duration instead of getting a second line per Service Request.
5. Expected rejections of outside input carry no stack trace: untrusted input could trigger them at will, and an exception's
   message can echo part of that input.

## Open questions

- **DCC and volume.** Does DCC accept the extra INFO volume in CloudWatch Logs, and does its log agent want `edt.interaction`
  routed separately? The 40% figure rests on measured line sizes but on guessed device and step counts (the Flow content is
  not final). [CONFIRM] with DCC.
- **Top-level fields.** The `structured-logging` requirement that fields are top-level is itself still [CONFIRM] with DCC; the
  new fields inherit that.
- **`edt.interface` values.** They copy IDs from the model; if the model's IDs change, the logs change with them. Is that
  coupling wanted, or should the field use stable names of our own?
- **Stack traces and untrusted data.** Whether an exception from a technical failure can ever carry a fragment of a message
  from outside is checked per call site in design.md; a review by someone who knows the DCC adapter code is wanted.
- **Sub-categories.** Whether one noisy boundary (KMS, Kafka) needs its own level later. Left out here.
- **Temporal's CamelCase keys and `temporal.workflow_id`** remain open from `structured-logging`.
