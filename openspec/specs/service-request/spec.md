# service-request Specification

## Purpose

Defines the Service Request the EDT sends to the DSP: its DUIS 5.5 shape, its Request ID, the Service Requests the placeholder Flow uses, and what the DUIS Signer checks before it signs. It describes behaviour at the Orchestrator to DUIS Signer and Orchestrator to DSP boundaries (model elements `edt.orchestrator.requestBuilder`, `edt.duisSigner.signingPolicy`).

## Requirements

### Requirement: A Service Request has the DUIS 5.5 shape
The Orchestrator SHALL build each Service Request as a DUIS 5.5 `Request` in the `http://www.dccinterface.co.uk/ServiceUserGateway` namespace with `schemaVersion` 5.5. Its Header SHALL carry `RequestID`, `CommandVariant`, `ServiceReference` and `ServiceReferenceVariant`, and its Body SHALL hold exactly one request element for that SRV.

#### Scenario: Meter, SRV 4.1.1
- **GIVEN** a device of type ESME or GSME
- **WHEN** the Orchestrator builds its Service Request
- **THEN** the Header has `ServiceReference` 4.1, `ServiceReferenceVariant` 4.1.1 and `CommandVariant` 1
- **AND** the Body is the empty element `ReadInstantaneousImportRegisters`

#### Scenario: Communications Hub, SRV 8.9
- **GIVEN** a device of type CHF
- **WHEN** the Orchestrator builds its Service Request
- **THEN** the Header has `ServiceReference` 8.9, `ServiceReferenceVariant` 8.9 and `CommandVariant` 1
- **AND** the Body is the empty element `ReadDeviceLog`

#### Scenario: The request names no device type
- **WHEN** the Orchestrator builds any Service Request
- **THEN** the request has no device type element; the target device is identified only by its Request ID

### Requirement: The Request ID names originator, device and counter
The `RequestID` of a Service Request SHALL be written as `originator ID:target device ID:counter`. The Service Request workflow ID SHALL still be derived from it, so that the Response Listener can find the workflow without any table.

#### Scenario: Request ID in the request
- **GIVEN** originator `90-B3-D5-1F-30-01-00-00`, target device `00-DB-12-34-56-78-90-A0` and counter 1000
- **WHEN** the Service Request is built
- **THEN** its `RequestID` is `90-B3-D5-1F-30-01-00-00:00-DB-12-34-56-78-90-A0:1000`

#### Scenario: Workflow ID
- **WHEN** a Service Request has Request ID `R`
- **THEN** its Service Request workflow ID is the one derived from `R`, as before this change

### Requirement: The EDT builds and signs Service Requests for five SRVs
The Orchestrator SHALL build, and the DUIS Signer SHALL accept, Service Requests for SRV 4.1.1, 8.9, 6.2.9, 6.30 and 11.2, each in the DUIS 5.5 shape with one empty request element as the Body. The Service Reference of 6.2.9 is 6.2, and of 6.30 and 11.2 it is the SRV itself. The request elements are `ReadDeviceConfigurationPaymentMode` (6.2.9), `ReadCHFSubGHzConfiguration` (6.30) and `ReadFirmwareVersion` (11.2). A request for any other SRV SHALL fail without a retry.

#### Scenario: Payment mode request
- **WHEN** the Orchestrator builds a request for SRV 6.2.9
- **THEN** its Header has Service Reference 6.2 and Service Reference Variant 6.2.9, and its Body is one `ReadDeviceConfigurationPaymentMode` element

#### Scenario: Firmware version request
- **WHEN** the Orchestrator builds a request for SRV 11.2
- **THEN** its Header has Service Reference 11.2 and Service Reference Variant 11.2, and its Body is one `ReadFirmwareVersion` element

#### Scenario: Sub GHz configuration request
- **WHEN** the Orchestrator builds a request for SRV 6.30
- **THEN** its Header has Service Reference 6.30 and Service Reference Variant 6.30, and its Body is one `ReadCHFSubGHzConfiguration` element

#### Scenario: SRV that is not built
- **WHEN** a request is asked for an SRV that is not one of the five
- **THEN** building fails without a retry and nothing is signed or sent

### Requirement: The allowlist covers the SRVs of the draft Flows
The DUIS Signer's allowlist for the Other User role SHALL allow SRV 6.2.9 for the device types ESME and GSME, SRV 6.30 for CHF and SRV 11.2 for ESME, GSME and CHF. [CONFIRM] These are placeholder values until DCC provides the allowlist per User Role.

#### Scenario: Meter reads its firmware version
- **GIVEN** a well-formed 11.2 request from the Other User role's originator, for a device of type ESME
- **WHEN** the DUIS Signer checks it
- **THEN** it signs the request and writes an audit event

#### Scenario: Hub-only SRV for a meter
- **WHEN** a 6.30 request arrives for a device of type ESME
- **THEN** the DUIS Signer refuses it and signs nothing

### Requirement: The DUIS Signer checks a request before it signs
The DUIS Signer SHALL stop at the first failing check and sign only a request that passes all of them, in this order: the request is valid against the schema, its SRV is on the allowlist of a User Role, its originator is the User ID of that role, and the device type is allowed for the SRV. The device type SHALL be received with the request, not read from it. [CONFIRM] that this input does not deviate from the model.

#### Scenario: Valid request
- **GIVEN** a well-formed 4.1.1 request from the originator of the Other User role, for a device of type ESME
- **WHEN** the DUIS Signer checks it
- **THEN** it signs the request and writes an audit event for the decision

#### Scenario: Not valid against the schema
- **WHEN** the request is not well-formed, is in another namespace, or has a Body that is not one request element
- **THEN** the DUIS Signer refuses it with the schema reason and signs nothing

#### Scenario: SRV not on the allowlist
- **WHEN** the request's SRV is on the allowlist of no User Role
- **THEN** the DUIS Signer refuses it and writes an audit event with the reason

#### Scenario: Device type not allowed for the SRV
- **WHEN** a 4.1.1 request arrives for a device of type CHF
- **THEN** the DUIS Signer refuses it and signs nothing

### Requirement: Structure is checked against a partial schema
Until DCC provides the DUIS XSD, the DUIS Signer SHALL check structure against a partial schema derived from the Reference Test Data Set examples, and that schema SHALL say so. [CONFIRM] Replace it with the DUIS XSD when DCC provides it.

#### Scenario: Partial schema is labelled
- **WHEN** the schema resource of the DUIS Signer is read
- **THEN** it states that it is derived from released examples and is not the DUIS schema

#### Scenario: A real example passes
- **WHEN** a request written from the 4.1.1 or 8.9 example in the released data set is checked
- **THEN** it passes the structure check
