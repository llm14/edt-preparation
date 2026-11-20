# structured-logging Specification

## Purpose

Defines the shape of the logs the four EDT containers write, so that the DCC Log Agent and anyone searching the logs find the same ECS fields in the same place, and so that the Orchestrator's workflow events can be filtered by field instead of read as sentences.

## Requirements

### Requirement: Packaged containers write one ECS JSON object per line
In the packaged profile each of the four containers SHALL write every log line to standard output as one JSON object in ECS format. In the development and test profiles it SHALL write plain text.

#### Scenario: Packaged application
- **WHEN** a container runs in the packaged profile and writes a log line
- **THEN** the line parses as one JSON object
- **AND** the object has `@timestamp`, `log.level`, `message`, `service.name` and `ecs.version`

#### Scenario: Nothing but JSON at start-up
- **WHEN** a container starts in the packaged profile
- **THEN** every line it writes to standard output, from the first one, is a JSON object, including lines written by the JVM before the application's logging starts

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
A log line SHALL NOT contain the same key twice. For a line that reports an exception, `error.message` and `error.type` SHALL be written once, taken from the exception; the application SHALL set them as its own fields only on a line that has no exception.

#### Scenario: Exception logged
- **WHEN** a line is written for an exception
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

### Requirement: Every boundary crossing is logged on the interaction logger
Each time an EDT container calls, or is called by, another system through an interface of the model (the Manifest Events queue, the Manifest Bucket, AWS KMS, the Temporal Server, the DSP HTTPS Gateway, Kafka and the certificate store), it SHALL write one INFO line for that crossing, on the logger `edt.interaction`, from the side that makes or receives the call. A poll that returns nothing SHALL NOT write a line.

#### Scenario: Manifest taken from the queue
- **GIVEN** a Manifest event is on the Manifest Events queue
- **WHEN** Batch Ingest receives it, reads the Manifest and its signature from the Manifest Bucket, checks the signature with AWS KMS, starts the Batch and deletes the message
- **THEN** it writes one line for each of those five crossings on `edt.interaction`, each with the `edt.interface` of its own interface and `event.outcome` `success`
- **AND** the line for starting the Batch has `edt.batch_id`

#### Scenario: Service Request posted to the gateway
- **WHEN** the Orchestrator posts a signed Service Request to the DSP HTTPS Gateway and the gateway answers
- **THEN** it writes one line with `edt.interface` of its HTTP interface to the gateway, `network.direction` `outbound`, the HTTP status code, `event.duration` and the Request ID

#### Scenario: Post to the gateway fails
- **WHEN** the Orchestrator cannot reach the gateway, or the gateway does not answer with success
- **THEN** it writes one line with `event.outcome` `failure` for that attempt, with the HTTP status code when the gateway answered

#### Scenario: DSP message arrives at the Response Listener
- **WHEN** the DSP HTTPS Gateway passes a Response or a DCC Alert to the Response Listener
- **THEN** the Response Listener writes one line with `network.direction` `inbound`, the Request ID and the message type
- **AND** the line says whether a waiting Service Request workflow was signalled

#### Scenario: Orchestrator receives the Response
- **WHEN** a waiting Service Request workflow is signalled with a Response or a DCC Alert
- **THEN** the Orchestrator writes one line with the Request ID and the message type
- **AND** the line is not written again when Temporal replays the workflow

#### Scenario: Orchestrator starts a Batch
- **WHEN** a Batch workflow starts for the first time
- **THEN** the Orchestrator writes one line with `edt.batch_id`
- **AND** it writes no such line when the Batch continues as new

#### Scenario: Nothing to take from the queue
- **WHEN** a poll of the Manifest Events queue returns no message
- **THEN** no line is written for it

#### Scenario: One-device run covers every interface it uses
- **WHEN** a one-device Manifest is processed from upload to the Batch result
- **THEN** the logs of each container contain at least one `edt.interaction` line for every interface of the model that this run uses on that container

### Requirement: Interaction lines have the same keys
A line on `edt.interaction` SHALL have `event.action`, `event.outcome` (`success` or `failure`), `edt.interface` (the ID of the interface in the model) and `network.direction` (`outbound` for a call the container makes, `inbound` for a call it receives), and `event.duration` for a call the container makes. [CONFIRM] with DCC that the interface IDs of the model are the values it wants in `edt.interface`, and that its log agent and CloudWatch can take the volume of these lines.

#### Scenario: Any interaction line
- **WHEN** a container writes a line on `edt.interaction`
- **THEN** the line has `event.action`, `event.outcome`, `edt.interface` and `network.direction`

#### Scenario: A call the container makes
- **WHEN** a container writes the line for a call it made
- **THEN** the line also has `event.duration`

### Requirement: Interaction lines carry identifiers and results, never content
A line on `edt.interaction` SHALL carry identifiers, status, sizes and durations only. It SHALL NOT carry the body of a Service Request or Response, a Manifest row, a signature, a key or the content of a certificate.

#### Scenario: Signed Service Request posted
- **WHEN** the Orchestrator writes the line for posting a signed Service Request
- **THEN** the line has the Request ID, the HTTP status code, the size of the body and the duration
- **AND** it has no part of the XML and no signature value

### Requirement: The level of interaction lines can be set on its own
The level of the logger `edt.interaction` SHALL be configurable in each container's configuration, separately from every other logger, with INFO as the default.

#### Scenario: Default
- **WHEN** no level is configured for `edt.interaction`
- **THEN** its INFO lines are written

#### Scenario: Interaction level turned down
- **WHEN** the level of `edt.interaction` is set to WARN
- **THEN** no INFO line is written on `edt.interaction`
- **AND** the audit events, the security alerts and the workflow lines are still written

### Requirement: The Batch ID has one field name in every container
The ID of a Batch SHALL be written under the field name `edt.batch_id` in every container that logs it, so that one search finds the lines of a Batch in all of them.

#### Scenario: Following a Batch across containers
- **GIVEN** a Manifest was ingested and its Batch has ended
- **WHEN** the logs of Batch Ingest and of the Orchestrator are searched for one value of `edt.batch_id`
- **THEN** the lines for starting the Batch and for ending it are both found
- **AND** no line carries the Batch ID under another field name

### Requirement: An expected rejection of outside input carries no stack trace
A line that reports an expected rejection of input from outside (an invalid DSP signature, an unreadable message, an invalid Manifest row, an undecodable Response) SHALL state the reason in `error.message` and SHALL NOT carry a stack trace, because input from outside can trigger it at will.

#### Scenario: Invalid DSP signature
- **WHEN** the Response Listener discards a message whose DSP signature is not valid
- **THEN** the line has the reason in `error.message`
- **AND** the line has no `error.stack_trace`

### Requirement: An unexpected technical failure logs its exception
A line that reports an unexpected technical failure (a queue poll that fails, a Manifest that could not be ingested, a signal that could not be delivered) SHALL carry the exception, so that the JSON line has `error.type`, `error.message` and `error.stack_trace`.

#### Scenario: Queue poll fails
- **WHEN** Batch Ingest cannot poll the Manifest Events queue
- **THEN** the line has `error.type`, `error.message` and `error.stack_trace`, each once
