# Spec Delta

## Purpose

Defines the shape of the logs the four EDT containers write, so that the DCC Log Agent and anyone searching the logs find the same ECS fields in the same place, and so that the Orchestrator's workflow events can be filtered by field instead of read as sentences.

## ADDED Requirements

### Requirement: Packaged containers write one ECS JSON object per line
In the packaged profile each of the four containers SHALL write every log line to standard output as one JSON object in ECS format. In the development and test profiles it SHALL write plain text.

#### Scenario: Packaged application
- **WHEN** a container runs in the packaged profile and writes a log line
- **THEN** the line parses as one JSON object
- **AND** the object has `@timestamp`, `log.level`, `message`, `service.name` and `ecs.version`

#### Scenario: Development run
- **WHEN** an application runs in the development or test profile
- **THEN** its log lines are plain text, not JSON

### Requirement: Fields the application adds are top-level fields
A field that an application adds to a log line SHALL be written as a top-level field under its own name, not inside a nested object. [CONFIRM] with DCC that its log agent and CloudWatch queries expect this.

#### Scenario: Event field at the top level
- **WHEN** Batch Ingest logs "Manifest received" with the field `event.action`
- **THEN** the JSON line has a top-level `event.action` with the value `manifest-received`
- **AND** the line has no nested `mdc` object

#### Scenario: Workflow and activity identifiers
- **WHEN** an Orchestrator workflow or activity writes a log line
- **THEN** the identifiers Temporal adds (workflow ID, run ID, activity type) are top-level fields of that line

### Requirement: A key appears at most once in a line
A log line SHALL NOT contain the same key twice. For a line that reports an exception, `error.message` and `error.type` SHALL be written once, from either the exception or the application's own fields.

#### Scenario: Exception logged
- **WHEN** a line is written for an exception and the code sets no `error.*` fields
- **THEN** `error.message` and `error.type` each appear once, taken from the exception

#### Scenario: Application sets the error fields
- **WHEN** a line sets `error.message` as a field and logs no exception
- **THEN** `error.message` appears once with the value the code set

#### Scenario: Both at once
- **WHEN** a line sets `error.message` as a field and also logs an exception
- **THEN** `error.message` still appears exactly once in the JSON line

### Requirement: Workflow log lines carry their data as fields
Each log line written by the Batch, Page, Flow and Service Request workflows SHALL carry the identifiers and results it reports (batch ID, page number, device ID, row number, Service Request ID, status, verdict and counts) as fields, with `event.action` naming the event. The message text SHALL be the same each time that event occurs.

#### Scenario: Batch ended
- **GIVEN** a Batch of one device has finished
- **WHEN** the Batch workflow logs that it ended
- **THEN** the line has `event.action` and fields for the batch ID, the number of devices, the number of invalid rows and the verdicts
- **AND** the message text does not contain the batch ID or any count

#### Scenario: Flow ended
- **WHEN** the Flow workflow logs the verdict for a device
- **THEN** the line has fields for the device ID, the row number, the Service Request status and the verdict

#### Scenario: Service Request ended
- **WHEN** the Service Request workflow logs that it ended
- **THEN** the line has fields for the Service Request ID and its final status

### Requirement: Replay does not repeat workflow log lines
A workflow log line SHALL be written once for each real occurrence of its event, and SHALL NOT be written again when Temporal replays the workflow.

#### Scenario: Orchestrator restarts while a Service Request waits
- **GIVEN** a Service Request workflow is waiting for its Response
- **WHEN** the Orchestrator restarts and Temporal replays that workflow
- **THEN** the log lines written before the restart are not written again
- **AND** the line that says the Service Request ended appears exactly once
