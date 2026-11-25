# Proposal

## Why

The Flow is a placeholder: whatever the Manifest row says, it sends one Service Request chosen from the device type (SRV 8.9 for
a Communications Hub, 4.1.1 for the rest, neither of them in DCC's list) and turns the answer into a Verdict. The model has a
Flow Catalogue ("the 3 approved Flows: the Service Requests to send, their order, and the decision after each Response") that the
code bypasses, and the Manifest's `flow` column is carried through but never read. DCC has now given a list of the Service Requests
it considers for non-communication triage (`samples/possible_flows.csv`) and released examples exist for every one of them
(RTDS 4.10.0). That is enough to build a first multi-step shape: two draft Flows, so that the Flow workflow, the Kafka step events and
the Verdict rules run against something close to a real Flow. DCC still decides the three approved Flows, so these two are drafts.

## What Changes

- **A Flow Catalogue exists, as data.** A Flow is a named, ordered list of steps (an SRV, the device types it may target, and
  what to do after its Response). The Flow workflow asks the catalogue for the steps of the row's `flow` and runs them one after the
  other. A `flow` that is not in the catalogue is refused, as the model says.
- **Two draft Flows** (written from `samples/possible_flows.csv`, listed in `samples/draft_flows.md`):
  - **Hub triage**, for a Communications Hub (CHF): SRV 8.9 Read Device Log, then SRV 6.30 Read CHF Sub GHz Configuration.
  - **Meter triage**, for an ESME or GSME: SRV 6.2.9 Read Device Configuration (Payment Mode), then SRV 11.2 Read Firmware Version.
- **The Flow publishes one `FlowStep` Kafka event per step** (the event already has a step number) and one `DeviceResult` at the end.
- **The Verdict is decided over the steps**, not from one outcome (rule in the design; [CONFIRM] with DCC).
- **Three new Service Requests** are built, signed, sent and decoded: SRV 6.2.9, 6.30 and 11.2, in the real DUIS 5.5 and MMC 5.5 shapes
  of the RTDS examples. SRV 8.9 already exists. SRV 4.1.1 stays supported by the builder, the signer and the decoder, but no
  Flow uses it any more.
- **The DUIS Signer's SRV allowlist** gains the three SRVs, per User Role (placeholder values until DCC provides the real ones).
- **The DSP simulator answers the three new SRVs** in the real Response shape, for success.
- **The Manifest `flow` column now chooses the Flow.** `device_type` stays in the row (the signer still needs it) and is checked against
  the Flow: a device type the Flow does not allow gives the row the Verdict `NOT_DIAGNOSED` without any request.
- Sample Manifests and the docs are updated to the two Flow names.
- **BREAKING for the sample Manifests:** `flow-1` is no longer a known Flow. Rows that name it are refused.

## Capabilities

### New Capabilities

- `flow-catalogue`: the approved Flows as data: how a Flow is named and looked up, the steps it runs and in what order, the device
  types it accepts, the decision after each step, how its Verdict is derived, and what is published to Kafka for each step.

### Modified Capabilities

- `service-request`: the requirement "The placeholder Flow chooses the SRV by device type" is replaced (the Flow, not the device
  type, chooses the SRV), and the SRVs that can be built, signed and checked grow from two (4.1.1, 8.9) to five.
- `dsp-response`: the requirement "A Response is decoded into Facts by SRV and device type" gains the three new SRVs and their Facts.

## Impact

- Code: `orchestrator` (`PlaceholderFlowWorkflowImpl` replaced by a Flow workflow that loops over catalogue steps, a catalogue
  activity or lookup, `RequestBuilderActivityImpl`, `RealResponseDecoder`, `ResultPublisherActivity` use), `duis-signer` (allowlist
  configuration, the partial schema for the three new request bodies), `dcc-tools/dsp-simulator` (`DspMessages`).
- The model's Flow Catalogue Activity (`edt.orchestrator.flowCatalogue`) is finally used. Whether it is a Temporal activity or a
  plain lookup is a design question.
- Configuration: `edt.policy.*` in the signer; possibly a catalogue file or properties in the Orchestrator.
- Samples and docs: `samples/manifests/*.csv`, `samples/draft_flows.md`, README, `HAPPY-PATH-MANUAL.md`.
- Kafka: `edt.flow-steps` now carries two steps per device instead of one. Nothing in this repository reads them.

## Out of scope

- DCC's three approved Flows, their final steps, order and decision rules: these two Flows are drafts.
- SRV 6.31, 6.32, 6.13, 6.2.4, 7.4, 8.2 and 12.1. They stay in `samples/possible_flows.csv` for later.
- Future dating (SRV 8.9 and 11.2 are "DSP future dated" for scheduled reports) and any critical Service Request.
- The hub chain: running 11.2 only after an 8.9 that shows a recent last communication, which needs the Communications Hub of a meter.
- Device types GPF, HCALCS and PPMID; the Manifest format and columns beyond the meaning of `flow`.
- Failure Responses (`MessageSuccess="false"`) from the simulator, the synchronous response, DCC Alerts, GBCS payloads and the real DUIS XSD.
- Per-step retries and any change of the retry model (CLAUDE.md open point 2).

## Open questions

1. **Hub chain.** The file says 11.2 is used "conditionally following an SRV 8.9 that shows a recent last communication". This change runs 11.2
   unconditionally after 6.2.9, on the same meter. [CONFIRM] with DCC how the chain should work. It needs a hub ID for each meter and a definition of "recent".
2. **6.2.4 or 6.2.9.** The file gives both the same purpose (end-to-end connectivity for SMETS1) and 6.2.9 also finds SMI misalignment. 6.2.9 is used here. [CONFIRM].
3. **The Verdict rule over several steps.** The proposed rule is in the design. DCC decides what each outcome should mean, including `MessageSuccess="false"`.
4. **When a step stops the Flow.** A step that ends `TIMED_OUT`, `FAILED_ALERT`, `REJECTED_BY_DSP` or `SEND_FAILED` is assumed to stop the Flow. [CONFIRM].
5. **Flow names.** `hub-triage` and `meter-triage` are ours. DCC gives the names of the approved Flows (this also touches the open Manifest format, CLAUDE.md open point 4).
6. **What a Flow does when the device type does not match** (for example an ESME row for the hub Flow) is our choice, not DCC's.
7. **Flat Facts for SRV 6.30.** Its Response is much larger than the others (channel lists, thresholds). Which values become Facts is a design choice. [CONFIRM] with DCC which ones triage needs.
8. **Signer allowlist per role** for the three new SRVs is placeholder, as for the existing ones. [CONFIRM].
9. **Model wording.** The model's Flow Catalogue Activity returns an approved Flow. The design keeps it as a Temporal activity (design D1), so there is no deviation; whether DCC's real catalogue comes from configuration or a file is open.
