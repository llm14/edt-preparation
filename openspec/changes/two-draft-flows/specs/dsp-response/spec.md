# Spec Delta

## ADDED Requirements

### Requirement: Responses to SRV 6.2.9 and 11.2 are decoded into Facts
The Orchestrator SHALL turn a Response to SRV 6.2.9 into the Facts `MessageSuccess` and `PaymentMode`, and a Response to SRV 11.2 into the Facts `MessageSuccess` and `FirmwareVersion`. [CONFIRM] the Fact names with DCC's Flows.

#### Scenario: Payment mode
- **GIVEN** a successful 6.2.9 Response with payment mode Prepayment
- **WHEN** the Orchestrator decodes it
- **THEN** the Facts are `MessageSuccess` true and `PaymentMode` Prepayment

#### Scenario: Firmware version
- **GIVEN** a successful 11.2 Response with firmware version 0123ABCD
- **WHEN** the Orchestrator decodes it
- **THEN** the Facts are `MessageSuccess` true and `FirmwareVersion` 0123ABCD

### Requirement: A Response to SRV 6.30 is decoded into scalar Facts
The Orchestrator SHALL turn a Response to SRV 6.30 into the Fact `MessageSuccess` and one Fact for each value that is a direct scalar child of the response element, named after that element (for example the duty cycle thresholds, `MaximumSubGHzChannelChangesPerWeek` and `GSMECurfew`). The channel lists SHALL NOT become Facts. [CONFIRM] with DCC which values triage needs.

#### Scenario: Thresholds are Facts, channel lists are not
- **GIVEN** a successful 6.30 Response with channel lists and a maximum of 4 channel changes per week
- **WHEN** the Orchestrator decodes it
- **THEN** the Facts include `MessageSuccess` true and `MaximumSubGHzChannelChangesPerWeek` 4, and no Fact is named after a channel

### Requirement: The new SRVs follow the rule that a device failure is recorded, not judged
For SRV 6.2.9, 6.30 and 11.2 the Orchestrator SHALL apply the same rule as for the other SRVs when the Response has `MessageSuccess="false"`: it records `MessageSuccess` and a debug summary, adds no value Facts, and the Service Request stays `RESPONDED`.

#### Scenario: Firmware read fails on the device
- **GIVEN** an 11.2 Response with `MessageSuccess="false"` and a failed result in its debug information
- **WHEN** the Orchestrator decodes it
- **THEN** the Facts are `MessageSuccess` false and a `DebugSummary`, with no `FirmwareVersion`, and the Service Request is `RESPONDED`

### Requirement: The DSP simulator answers the SRVs of the draft Flows
The DSP simulator SHALL answer a Service Request for SRV 6.2.9, 6.30 or 11.2 with a Response in the real MMC 5.5 shape, with success values taken from the Reference Test Data Set examples, so that the draft Flows run end to end against it. The device behaviours chosen by the device ID apply to every step of a Flow.

#### Scenario: Simulator answers a firmware read
- **GIVEN** a device whose behaviour is to respond
- **WHEN** the simulator receives an 11.2 request for it
- **THEN** it sends a signed Response whose `ReadFirmwareVersionRsp` has `MessageSuccess="true"`

#### Scenario: Behaviour applies to every step
- **GIVEN** a device whose behaviour is to stay silent
- **WHEN** a Flow sends its first Service Request
- **THEN** the simulator answers it with nothing, as for any other SRV
