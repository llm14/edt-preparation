# Design

## Context

Read from the repository and from the running cluster (2026-11-19), not assumed:

- All four apps use `quarkus-logging-json` with `log-format=ecs` (packaged profile) and plain text in dev and test. Fields are
  added with `LogFields`, a small MDC helper that exists once per app (four copies, by the decision not to share a module).
- In the real JSON lines the added fields sit inside `"mdc": {...}`: `event.action`, `event.kind`, `event.category`,
  `event.outcome` and the `edt.*` fields. Temporal's SDK adds its own MDC keys to workflow and activity lines
  (`WorkflowId`, `RunId`, `Namespace`, `TaskQueue`, `WorkflowType`, `ActivityId`, `ActivityType`, `Attempt`).
- Quarkus 3.40 has `quarkus.log.console.json.mdc.flat-fields` (default `false`): "MDC entries are written as root-level JSON
  fields instead of being grouped under a nested mdc object" (read in the `quarkus-logging-json` sources).
- Loggers: 14 files use JBoss Logging, 5 use SLF4J (the Temporal workflow loggers from `Workflow.getLogger`). Only the
  `adapter` and `infrastructure` layers log; the architecture tests keep `entity` and `usecase` free of frameworks.
- The five workflow classes log 8 lines with the data inside the message (`"Batch {} ended: {} devices, ..."`).
- In ECS mode the formatter itself writes `error.message` and `error.type` when a line carries an exception. The code also sets
  `error.message` as an MDC field in 10 places and `error.type` in 1 of them; none passes an exception on the same line today.
- Tests are plain JUnit 5 plus ArchUnit, with no `@QuarkusTest`. The orchestrator runs its workflows in memory with
  `TestWorkflowEnvironment`.

Model elements: the "Local ECS JSON logs" interfaces `edt.batchIngestProvFile` and `edt.orchestratorProvFile` ("each Flow and
Batch finished"), read by the DCC Log Agent (`dcc.logAgent`) into CloudWatch Logs (`dcc.cloudWatch`). The model names ECS JSON
but not where fields sit. See proposal.md for the motivation.

## Goals / Non-Goals

**Goals:**
- Application fields at the top level of the JSON line, using the setting Quarkus already provides.
- Workflow log lines whose data is in fields, with a constant message per event.
- A check of the real log shape that fails when it drifts.

**Non-Goals:**
- Changing logger libraries, renaming existing fields, or defining a full field vocabulary (see proposal, out of scope).

## Decisions

### 1. Turn on `quarkus.log.console.json.mdc.flat-fields=true`, in each app's `application.properties`
One line per app, next to `log-format=ecs`. It applies wherever JSON is on (the packaged profile).
*Rejected:* leaving the fields nested and asking the log agent side to read `mdc.*` (puts a Quarkus detail on the consumer, and
the keys stop being ECS paths); a custom `JsonProvider` or formatter (code to own for something a setting already does);
swapping to a separate ECS formatter library (a new dependency that replaces the one Quarkus provides, for no gap we can name).

### 2. Fields keep going through the MDC (`LogFields`), not through SLF4J's key-value API
*Why:* SLF4J 2's `atInfo().addKeyValue(...)` does not produce fields on this stack. The bridge `slf4j-jboss-logmanager` 2.0.2 has
no key-value support (its classes are the logger, factory, MDC adapter and bindings), and SLF4J's own fallback merges the
key-values into the message text. The MDC is what the JSON formatter turns into fields.
*Rejected:* the fluent key-value API (it would put the data back into the message).

### 3. Loggers stay as they are
JBoss Logging in adapters and infrastructure, SLF4J in workflows. *Why:* workflows must use `Workflow.getLogger`, which is
SLF4J and silent during replay; switching the other 14 files to SLF4J would touch many files and change nothing that is
observable. *Rejected:* unifying on SLF4J in this change.

### 4. Workflow lines use `LogFields` around the log call only
```java
try (var fields = LogFields.of("event.action", "batch-ended", "edt.batch_id", batchId, ...)) {
    LOG.info("Batch ended");
}
```
The block holds only the log call, with no blocking Temporal call inside. *Why:* the MDC belongs to a thread, and workflow code
runs on Temporal's threads and yields; keeping the block free of waits means nothing can leak into another workflow. Replay
stays safe because `Workflow.getLogger` writes nothing while replaying (the put and remove are harmless).

**Spike result (task 1.1, 2026-11-19, a throw-away test in the orchestrator with its own small workflow and
`TestWorkflowEnvironment`; deleted afterwards):**
- 30 workflows ran at the same time. Each one's wrapped log line carried its own fields (`edt.name` equalled that workflow's
  `WorkflowId` every time), so nothing crossed between workflows.
- Inside the block the MDC held our fields next to Temporal's (`Namespace`, `RunId`, `TaskQueue`, `WorkflowId`,
  `WorkflowType`): one map. Before the block, after it, and after a `Workflow.sleep` (where the thread yields) our fields
  were gone, and a workflow that started later on the same worker never saw them.
- Replay was exercised for real: each recorded history was replayed with `WorkflowReplayer`, which ran the workflow body
  again 30 times. The workflow's own logger wrote **0** lines during those replays. (180 other records appeared, all Temporal's
  own shutdown messages from the replayer; they are not workflow lines.)
- A first attempt used a workflow cache size of 0 to force replays; the body still ran only once per workflow, so that
  version proved nothing about replay and was replaced by `WorkflowReplayer`.
- Not tested: a block that contains a blocking call. The rule above stays as a precaution, not as something shown necessary.

Field names follow the ones already in use (`edt.request_id`, `edt.manifest.row`, `edt.manifest.invalid_rows`) and add
`edt.batch_id`, `edt.page`, `edt.device_id`, `edt.status`, `edt.verdict`, `edt.verdicts`, `edt.devices`, `edt.alert_code`,
`edt.refusal_reason`. Values stay strings; `edt.verdicts` holds the same text the message holds today (for example
`RESPONDS=1`), because the verdict names are not a fixed list.

| Today (message text) | New message | `event.action` | Fields |
|---|---|---|---|
| `Batch {} continues as new at Page {}` | `Batch continues as new` | `batch-continued-as-new` | `edt.batch_id`, `edt.page` |
| `Batch {} ended: {} devices, {} invalid rows, Verdicts {}` | `Batch ended` | `batch-ended` | `edt.batch_id`, `edt.devices`, `edt.manifest.invalid_rows`, `edt.verdicts` |
| `Page {} ended: {} devices, Verdicts {}` | `Page ended` | `page-ended` | `edt.page`, `edt.devices`, `edt.verdicts` |
| `Flow for row {} ({}): {} -> Verdict {}` | `Flow ended` | `flow-ended` | `edt.manifest.row`, `edt.device_id`, `edt.status`, `edt.verdict` |
| `DCC Alert {} for {}: keep waiting` | `DCC Alert received, still waiting` | `dcc-alert-received` | `edt.alert_code`, `edt.request_id` |
| `Service Request {} ended: {}` | `Service Request ended` | `service-request-ended` | `edt.request_id`, `edt.status` |
| `Asking the DUIS Signer to sign` | unchanged | `sign-probe-started` | none |
| `Sign outcome: {} {}` | `Sign outcome` | `sign-probe-outcome` | `edt.status`, `edt.refusal_reason` (only when there is one) |

### 5. Two checks of the log shape, plus one that depends on the spike
1. **A plain unit test in each app** reads the app's own `application.properties` and asserts the JSON, ECS and flat-fields
   settings, and that dev and test stay plain text. Fast, and it fails if the setting is removed.
2. **`deploy/check-log-shape.sh`** reads the real logs of the four pods (`kubectl logs`) and fails if any JSON line does not
   parse, lacks the required ECS keys, has an `mdc` object, or repeats a key (detected while parsing). It checks the real
   packaged app, which a unit test cannot, in the style of `deploy/check-env-keys.sh`. It needs the cluster running.
3. **A workflow test** (orchestrator, with `TestWorkflowEnvironment`) that runs a workflow and reads its log records to
   assert the fields. The spike showed this works in a plain unit test: the test classpath already has the SLF4J bridge and
   the JBoss log manager, and an `ExtHandler` added to `LogContext.getLogContext().getLogger("")` receives the workflow's
   records, each with its MDC (`getMdcCopy()`), without setting any JVM property. For replay, use `WorkflowReplayer` on a
   recorded history and count only the records of the workflow's own logger (the replayer adds its own shutdown logs).
*Rejected:* a `@QuarkusTest` that captures console output (adds a test framework to every app and the capture is awkward);
a unit test that builds the JSON formatter by hand (it would have to copy Quarkus's own ECS wiring, so it could pass while the
real app differs).

### 6. `error.message` and `error.type` have one source per line
The rule: a line sets them as fields **or** logs an exception, never both. Today no line does both, and the log-shape
check on the real logs (decision 5) would catch a line that does. *Rejected:* making `LogFields` refuse the key (it cannot know whether the call also passes an
exception).

### 7. Documentation
README Step 4 shows the nested `mdc` in two examples and says custom fields appear under `mdc`; update them and add the
rules (fields top-level, one key once, `error.*` single-sourced). `HAPPY-PATH-MANUAL.md` line 43 quotes
`Service Request ... ended: RESPONDED`; update it to the new message plus its fields.

## Risks / Trade-offs

- **DCC's log agent may expect the nested `mdc`** → the top-level requirement is marked [CONFIRM]; rolling back is one property
  per app (`flat-fields=false`). Ask DCC before this goes beyond local use.
- **Temporal's CamelCase keys become top-level** (`WorkflowId`, `RunId`, `Attempt`, `Namespace`, `TaskQueue`, ...) → accepted
  and listed in the spec scenario; no ECS field has these names, so no collision. Renaming is an open question.
- **A MDC key equal to a built-in key would be written twice** (the formatter writes `event.sequence`, `log.level`, `message`,
  and so on itself) → no app field uses a built-in name today; the log-shape check detects it if one appears.
- **MDC state leaking between workflows** → the block wraps only the log call. The spike (decision 4) showed no leak across
  30 concurrent workflows, across a yield, or into later workflows, and no workflow line written during replay, so scope B
  stays. It did not test a block that contains a blocking call, which is why the rule remains.
- **Values are strings** (counts included) → fine for `edt.*` fields; if DCC maps a field as a number it would need a decision.
- **Changed message text** for 8 workflow lines → no test asserts them and one document quotes one (updated in 7).

## Migration Plan

No data migration. Deploy with `deploy/edt.sh up` (or Compose) and run `deploy/check-log-shape.sh`. Rollback: set
`quarkus.log.console.json.mdc.flat-fields=false`, which restores the nested shape (the workflow field changes are harmless
either way).

## Open Questions

- DCC's log agent and CloudWatch: top-level fields, or nested `mdc`? Affects `dcc.logAgent` and the spec's [CONFIRM] (to be
  asked of DCC; local work does not depend on the answer).
- Should Temporal's CamelCase keys be renamed to ECS-style names (for example under `temporal.*`)? Left as they are here.
- Keep `temporal.workflow_id` where activities add it, next to Temporal's own `WorkflowId`? Left as is.
- Should a later change define the full `event.action` and `edt.*` vocabulary as a spec (option C of the exploration)?
