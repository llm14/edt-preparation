# Proposal

## Why

The model says every EDT container writes ECS JSON logs that the DCC Log Agent (`dcc.logAgent`) reads and sends to CloudWatch,
and that the Orchestrator logs "each Flow and Batch finished" (`edt.orchestratorProvFile`). The apps already write ECS JSON, but
reading the real lines from the running cluster shows three gaps:

1. **ECS names in the wrong place.** The fields the code adds (`event.action`, `event.kind`, `event.category`, `event.outcome`,
   the `edt.*` fields) sit inside a nested `"mdc"` object. A consumer that expects ECS looks for them at the top level.
2. **Workflow lines keep their data in the message text.** `Batch {} ended: {} devices, ...` and the like carry the batch ID, the
   counts and the verdicts only inside the sentence, so they cannot be searched or filtered by field.
3. **Nothing checks the shape.** No test asserts what a log line looks like, so a change to the logging setup or to a field
   could break the log agent's input without anyone noticing.

## What Changes

- Turn on `quarkus.log.console.json.mdc.flat-fields` in the four apps, so fields added through `LogFields` become top-level
  JSON fields (a setting Quarkus 3.40 already has; it is off today).
- Give the workflow log lines (Batch, Page, Flow, Service Request, and the sign probe) their data as fields, with a constant
  message text per event.
- Add tests that check the shape of a log line: fields at the top level, no `mdc` object, no key written twice, and that each
  app's configuration really turns the setting on.
- Keep `error.message` and `error.type` single-sourced: either the formatter writes them for a logged exception, or the code
  sets them as fields, never both on the same line.
- Update README Step 4 and the one line in `HAPPY-PATH-MANUAL.md` that quotes a workflow message.
- **BREAKING for anything that reads the logs by field path:** a query or alert on `mdc.event.action` will no longer match;
  the field is `event.action`. Nothing in this repository does that today, but DCC's side must be checked (open question).

## Capabilities

### New Capabilities

- `structured-logging`: the shape of the logs the four containers write: ECS JSON per line in the packaged apps, application
  fields at the top level, each key at most once, and workflow log lines that carry their data as fields. It applies to all
  four containers, so it is cross-cutting.

### Modified Capabilities

None. The only main spec is `local-cluster`, which is not about logging.

## Impact

- Code: `application.properties` of the four apps (one line each) and the workflow classes in the Orchestrator
  (`BatchWorkflowImpl`, `PageWorkflowImpl`, `PlaceholderFlowWorkflowImpl`, `ServiceRequestWorkflowImpl`,
  `SignProbeWorkflowImpl`). The workflow lines are the only places where message texts change.
- Tests: a plain JUnit configuration test in each app (no `@QuarkusTest`), workflow tests in the Orchestrator, and a script
  that checks the shape of the real logs on the cluster (`deploy/check-log-shape.sh`). No new dependency is expected.
- Docs: README Step 4 (its examples show the old nested `mdc`), `HAPPY-PATH-MANUAL.md` line 43.
- Model fit: no new container or relation. It is the "Local ECS JSON logs" interface of each container
  (`edt.batchIngestProvFile`, `edt.orchestratorProvFile`) and the DCC Log Agent that reads it.
- Runs unchanged on Docker Compose and on the local cluster, since only the app configuration and code change.

## Out of scope

- Moving the code from JBoss Logging to SLF4J. The apps keep their current loggers (JBoss Logging in adapters and
  infrastructure, SLF4J in workflows, where Temporal requires it).
- A written vocabulary of every `event.action` value and `edt.*` field (a later change, if wanted).
- Renaming fields that exist today, renaming Temporal's own context keys (`WorkflowId`, `RunId`, ...), tracing (`trace.id`),
  log levels, retention, and anything on the DCC or CloudWatch side.
- A shared logging module: each app keeps its own `LogFields`, as decided for the build layout.

## Assumptions made (confirm or correct)

1. Scope is A and B from the exploration: truly ECS (flat fields and a shape test) and workflow lines with fields. Not C or D.
2. The text of the workflow messages may change (to a constant sentence per event, with the values moved into fields). No test
   asserts the old texts, and only one document quotes one.
3. Field values stay strings, as they are today (the MDC holds strings). Counts such as `edt.devices` are therefore strings.

## Open questions

- **What the DCC log agent expects.** CLAUDE.md and the model say only "ECS JSON". If DCC's agent or CloudWatch queries rely on a
  nested `mdc` object, flattening breaks them. To be confirmed with DCC; until then the top-level rule is marked [CONFIRM].
- **MDC in workflow code.** Workflow code is replayed and runs on Temporal's own threads. Whether `LogFields` is safe there
  (no leaking between workflows, no duplicate lines on replay) is to be checked first; the plan puts that spike before any
  workflow change, and says what to do if the answer is no.
- **Temporal's CamelCase keys** (`WorkflowId`, `RunId`, `Attempt`, ...) become top-level fields too once MDC is flat. They are
  accepted as they are; whether to rename them to ECS-style names is left open.
- **`temporal.workflow_id`**: some activities already add this field next to Temporal's own `WorkflowId`. Whether to keep both
  is left open.
