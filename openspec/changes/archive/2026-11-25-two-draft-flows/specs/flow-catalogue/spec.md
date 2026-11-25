# Spec Delta

## Purpose

Defines the Flows the EDT runs on a device: how a Flow is named and found in the catalogue, the Service Requests it sends and in what order, when it stops, how its Verdict is decided and what is published to Kafka for each step. It describes behaviour at the Orchestrator to DUIS Signer, Orchestrator to DSP and Orchestrator to Kafka boundaries (model elements `edt.orchestrator.flowCatalogue`, `edt.orchestrator.requestBuilder`, `edt.orchestrator.resultPublisher`). The two Flows described here are drafts.

## ADDED Requirements

### Requirement: A Flow is named by the Manifest row and found in the catalogue
The Orchestrator SHALL run, for each Manifest row, the Flow named in the row's `flow` column, taken from the Flow Catalogue. [CONFIRM] The Manifest format and the Flow names are DCC's to define.

#### Scenario: Known Flow
- **GIVEN** a row whose `flow` is `meter-triage`
- **WHEN** the Orchestrator runs the row
- **THEN** it runs the steps of the `meter-triage` Flow

#### Scenario: Unknown Flow
- **GIVEN** a row whose `flow` is not in the catalogue
- **WHEN** the Orchestrator runs the row
- **THEN** no Service Request is sent, the device Verdict is `NOT_DIAGNOSED`, and the reason is logged with the Flow name

### Requirement: A row with a device type the Flow does not accept is not diagnosed
The Orchestrator SHALL send nothing for a row whose device type the Flow's first step does not accept. The Verdict SHALL be `NOT_DIAGNOSED` and the Flow SHALL NOT end as failed. [CONFIRM] What the EDT does with such a row is our choice, not DCC's.

#### Scenario: Wrong device type for the Flow
- **GIVEN** a row `00-DB-12-34-56-78-92-A0,ESME,hub-triage`
- **WHEN** the Orchestrator runs the row
- **THEN** no Service Request is sent, the Verdict is `NOT_DIAGNOSED`, and the Batch counts the row as a device with that Verdict, not as a failed Flow

### Requirement: A Flow sends its Service Requests one after the other
A Flow SHALL send the Service Requests of its steps in the order of the catalogue, one at a time, and SHALL send the next only after the previous one has ended. A Service Request that has ended in any way SHALL NOT be sent again.

#### Scenario: Two steps in order
- **GIVEN** a Flow of two steps and a device that answers both
- **WHEN** the Flow runs
- **THEN** the second Service Request is sent only after the Response to the first has arrived, and exactly two Service Requests are sent

### Requirement: A Flow stops at the first step that does not get a Response
A Flow SHALL continue to its next step only when the step ended with a Response (status `RESPONDED`). Any other ending (timeout, DCC Alert N12 or N13, refusal by the signer, rejection by the DSP, send failure, undecodable Response) SHALL end the Flow. A Response with `MessageSuccess="false"` is a Response, so the Flow continues. [CONFIRM] with DCC when a Flow should stop.

#### Scenario: First step times out
- **GIVEN** a Flow of two steps and a device that never answers
- **WHEN** the first step reaches its timeout
- **THEN** the second Service Request is not sent and the Flow ends

#### Scenario: Device reports it failed
- **GIVEN** a first step whose Response has `MessageSuccess="false"`
- **WHEN** the Flow decides what to do next
- **THEN** it sends the second Service Request

### Requirement: Every step has its own Request ID
Each Service Request of a Flow SHALL have its own Request ID, and the counter part of the Request IDs of one Flow SHALL be strictly increasing, so that two steps never share a Request ID or a workflow ID. [CONFIRM] The counter rule is the placeholder one.

#### Scenario: Two steps, two Request IDs
- **WHEN** a Flow sends two Service Requests to one device
- **THEN** their Request IDs have the same originator and device, and different counters, the second greater than the first

### Requirement: The Verdict is decided over all steps
The Verdict of a device SHALL be `RESPONDS` if at least one step ended with a Response; otherwise `NO_RESPONSE` if the first step ended by timeout or by DCC Alert N12 or N13; otherwise `NOT_DIAGNOSED`. [CONFIRM] The Verdict rules are DCC's to define.

#### Scenario: All steps answer
- **WHEN** both steps of a Flow end with a Response
- **THEN** the Verdict is `RESPONDS`

#### Scenario: Answers the first step, silent on the second
- **WHEN** the first step ends with a Response and the second times out
- **THEN** the Verdict is `RESPONDS`

#### Scenario: Silent from the start
- **WHEN** the first step times out
- **THEN** the Verdict is `NO_RESPONSE`

#### Scenario: Never sent
- **WHEN** the signer refuses the first Service Request
- **THEN** the Verdict is `NOT_DIAGNOSED`

### Requirement: Each step is published to Kafka
The Orchestrator SHALL publish one `FlowStep` event for each step that was run, with the step number, the SRV, the Request ID, the status and the Facts, and one `DeviceResult` event for the device. The event ID of a step SHALL be derived from the Flow workflow ID and the step number, so that a consumer can drop a duplicate.

#### Scenario: Two steps run
- **WHEN** a device runs both steps of a Flow
- **THEN** two `FlowStep` events (step 1 and step 2) and one `DeviceResult` are published, all keyed by the device ID

#### Scenario: Flow stops after the first step
- **WHEN** the first step times out
- **THEN** one `FlowStep` event (step 1, status `TIMED_OUT`) and one `DeviceResult` are published

### Requirement: The hub-triage Flow
The `hub-triage` Flow SHALL accept devices of type CHF and SHALL send SRV 8.9 (Read Device Log) and then SRV 6.30 (Read CHF Sub GHz Configuration). [CONFIRM] This Flow is a draft written from DCC's list of candidate Service Requests; DCC decides the approved Flows.

#### Scenario: Communications Hub answers both
- **WHEN** the `hub-triage` Flow runs for a CHF that answers
- **THEN** the steps are 8.9 then 6.30, and the Verdict is `RESPONDS`

### Requirement: The meter-triage Flow
The `meter-triage` Flow SHALL accept devices of type ESME and GSME and SHALL send SRV 6.2.9 (Read Device Configuration, Payment Mode) and then SRV 11.2 (Read Firmware Version). [CONFIRM] This Flow is a draft; SRV 6.2.4 is the alternative to 6.2.9 and the condition of 11.2 on an earlier SRV 8.9 is not implemented.

#### Scenario: Meter answers both
- **WHEN** the `meter-triage` Flow runs for an ESME or a GSME that answers
- **THEN** the steps are 6.2.9 then 11.2, and the Verdict is `RESPONDS`
