# Spec Delta

## REMOVED Requirements

### Requirement: The placeholder Flow chooses the SRV by device type
**Reason**: The Service Request a device receives is chosen by the Flow named in the Manifest row and its catalogue steps, not by the device type alone.
**Migration**: See the `flow-catalogue` capability (requirements "A Flow is named by the Manifest row and found in the catalogue", "The hub-triage Flow" and "The meter-triage Flow"). SRV 4.1.1 stays buildable and signable but no Flow sends it.

## ADDED Requirements

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
