# dsp-response Specification

## Purpose

Defines how a Response from the DSP is recognised, matched to the Service Request that is waiting for it, and turned into Facts. It describes behaviour at the DSP Gateway to Response Listener to Temporal and Orchestrator boundaries (model elements `edt.responseListener`, the Workflow Signaller and the Service Request workflow).

## Requirements

### Requirement: A Response is recognised by its structure
The Response Listener SHALL treat a message as a Response when its root is `GBCSResponse` in the `http://www.dccinterface.co.uk/ResponseAndAlert` namespace and its Body holds a `ResponseMessage`. A message whose Body holds a `DeviceAlertMessage` SHALL NOT be treated as a Response. [CONFIRM] that this is what the DSP delivers (proposal open question 4).

#### Scenario: Response
- **WHEN** the DSP Gateway posts a `GBCSResponse` whose Body holds a `ResponseMessage` with `ReadInstantaneousImportRegistersRsp`
- **THEN** the Response Listener reads it as a Response

#### Scenario: Device alert
- **WHEN** the DSP Gateway posts a `GBCSResponse` whose Body holds a `DeviceAlertMessage`
- **THEN** the Response Listener refuses it as unreadable, logs the reason without a stack trace, and signals no workflow
- **AND** [CONFIRM] what the EDT should do with device alerts, which the non-critical Service Requests it sends are not expected to produce

### Requirement: The Request ID is rebuilt from the Response header
A Response carries no Request ID. The Response Listener SHALL rebuild it as `BusinessTargetID:BusinessOriginatorID:OriginatorCounter` from the Response header. [CONFIRM] with DCC that this holds for every SRV.

#### Scenario: Rebuilt Request ID
- **GIVEN** a Response header with `BusinessTargetID` `90-B3-D5-1F-30-01-00-00`, `BusinessOriginatorID` `00-DB-12-34-56-78-90-A0` and `OriginatorCounter` 1000
- **WHEN** the Response Listener reads it
- **THEN** the Request ID is `90-B3-D5-1F-30-01-00-00:00-DB-12-34-56-78-90-A0:1000`

#### Scenario: Header incomplete
- **WHEN** a Response lacks one of the three header fields
- **THEN** the Response Listener refuses it as unreadable and signals no workflow

### Requirement: A Response through the Communications Hub uses the supplementary fields
When the Response header has `SupplementaryRemotePartyID`, the Response Listener SHALL rebuild the Request ID as `SupplementaryRemotePartyID:BusinessOriginatorID:SupplementaryRemotePartyCounter`. [CONFIRM] This rests on one released sample, the SRV 8.9 Response.

#### Scenario: SRV 8.9 Response
- **GIVEN** a Response header with `SupplementaryRemotePartyID` `90-B3-D5-1F-30-01-00-00`, `BusinessOriginatorID` `00-DB-12-34-56-78-90-A2` and `SupplementaryRemotePartyCounter` 1000
- **WHEN** the Response Listener reads it
- **THEN** the Request ID is `90-B3-D5-1F-30-01-00-00:00-DB-12-34-56-78-90-A2:1000`

### Requirement: A Response signals the waiting Service Request workflow
The Response Listener SHALL check the DSP signature of a Response and, when it is valid, signal the Service Request workflow whose ID is derived from the rebuilt Request ID. An invalid signature SHALL NOT signal any workflow.

#### Scenario: Valid Response
- **GIVEN** a Service Request workflow is waiting for Request ID `R`
- **WHEN** a Response with a valid DSP signature and rebuilt Request ID `R` arrives
- **THEN** that workflow is signalled with the Response

#### Scenario: Invalid signature
- **WHEN** a Response arrives whose content changed after the DSP signed it
- **THEN** no workflow is signalled and the Response is discarded

### Requirement: A Response is decoded into Facts by SRV and device type
The Orchestrator SHALL turn a Response into Facts by SRV and device type. For SRV 4.1.1 on an ESME the Facts are the active and reactive import registers, plus the secondary active import register on a twin-element meter, each with its value and unit. On a GSME the Fact is the consumption register, with its value and unit. For SRV 8.9 on a CHF there is one Fact per device in the log, with its last communication time and link quality. [CONFIRM] the Fact names with DCC's Flows.

#### Scenario: Electricity meter
- **GIVEN** a successful 4.1.1 Response for an ESME with active import 10 Wh and reactive import 10 varh
- **WHEN** the Orchestrator decodes it
- **THEN** the Facts hold both registers, each with its value and unit

#### Scenario: Gas meter
- **GIVEN** a successful 4.1.1 Response for a GSME with consumption 12.345 m3
- **WHEN** the Orchestrator decodes it
- **THEN** the Facts hold the consumption register with value 12.345 and unit m3

#### Scenario: Communications Hub
- **GIVEN** a successful 8.9 Response listing two devices
- **WHEN** the Orchestrator decodes it
- **THEN** the Facts hold one entry per listed device with its last communication time and link quality

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

### Requirement: The outcome of the device is recorded, not judged
The Orchestrator SHALL record `MessageSuccess` of a decoded Response as a Fact. When it is `false` it SHALL also record a summary of the result strings in the debug information of the Response. The Service Request SHALL stay `RESPONDED`, and no Verdict rule changes. [CONFIRM] What `MessageSuccess="false"` means for a Verdict is DCC's to define.

#### Scenario: Success
- **WHEN** a Response has `MessageSuccess="true"`
- **THEN** the Facts include `MessageSuccess` with value `true` and no debug summary

#### Scenario: Electricity meter reports a fault
- **GIVEN** a 4.1.1 Response with `MessageSuccess="false"` and COSEM debug information `hardware-fault`
- **WHEN** the Orchestrator decodes it
- **THEN** the Facts include `MessageSuccess` `false` and a debug summary containing `hardware-fault`
- **AND** no register Fact is recorded for a value the Response does not contain
- **AND** the Service Request outcome is `RESPONDED`

#### Scenario: Gas meter reports a fault
- **GIVEN** a 4.1.1 Response with `MessageSuccess="false"` and ZigBee debug information `HARDWARE_FAILURE`
- **WHEN** the Orchestrator decodes it
- **THEN** the Facts include `MessageSuccess` `false` and a debug summary containing `HARDWARE_FAILURE`

### Requirement: The new SRVs follow the rule that a device failure is recorded, not judged
For SRV 6.2.9, 6.30 and 11.2 the Orchestrator SHALL apply the same rule as for the other SRVs when the Response has `MessageSuccess="false"`: it records `MessageSuccess` and a debug summary, adds no value Facts, and the Service Request stays `RESPONDED`.

#### Scenario: Firmware read fails on the device
- **GIVEN** an 11.2 Response with `MessageSuccess="false"` and a failed result in its debug information
- **WHEN** the Orchestrator decodes it
- **THEN** the Facts are `MessageSuccess` false and a `DebugSummary`, with no `FirmwareVersion`, and the Service Request is `RESPONDED`

### Requirement: A Response that cannot be decoded ends the Service Request as failed
When the Orchestrator cannot decode a Response (not well-formed, no Response element for the SRV, or an SRV and device type it has no decoding for), the Service Request SHALL end as `DECODE_FAILED` with the reason, as before this change, and SHALL NOT be sent again.

#### Scenario: Unknown combination
- **WHEN** a Response arrives for an SRV and device type that have no decoding
- **THEN** the Service Request ends `DECODE_FAILED` with the reason

### Requirement: DCC Alerts and the synchronous response keep the placeholder format
Until DCC provides samples, a DCC Alert (N12, N13, N53) and the synchronous response of the DSP SHALL still be read in the placeholder format, and a DCC Alert N12 or N13 SHALL still end the waiting Service Request as failed. [CONFIRM] The real formats with DCC.

#### Scenario: DCC Alert N12
- **GIVEN** a Service Request workflow is waiting
- **WHEN** a DCC Alert N12 for its Request ID arrives
- **THEN** the Service Request ends `FAILED_ALERT` and is not sent again

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
