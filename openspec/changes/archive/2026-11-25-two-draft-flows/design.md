# Design

## Context

Why and what: see `proposal.md`. This design covers how the Flow becomes a catalogue-driven loop over steps.

What exists today (read from the code, not inferred):

- `PlaceholderFlowWorkflowImpl` (the Flow workflow): chooses an SRV from the device type, builds a `RequestId` from the workflow time, starts one
  `ServiceRequestWorkflow` as a child, maps the outcome to a Verdict, publishes `step-1` and the `DeviceResult`. `FlowInput` already carries
  `flow` (the row's column), which nothing reads.
- `ServiceRequestWorkflow` takes a `ServiceRequestInput` (request ID, batch, originator, device, device type, SRV) and returns a `ServiceRequestOutcome`
  with a status, Facts and an alert code. It never retries a sent request. Its statuses are `RESPONDED`, `DECODE_FAILED`, `REFUSED_BY_SIGNER`,
  `REJECTED_BY_DSP`, `SEND_FAILED`, `FAILED_ALERT`, `TIMED_OUT`.
- Per SRV, four places know it: `RequestBuilderActivityImpl` (service reference and body element), the DUIS Signer allowlist and partial
  schema, `RealResponseDecoder` (the `Rsp` element) and the simulator's `DspMessages`.
- The model has a Flow Catalogue Activity (`edt.orchestrator.flowCatalogue`), "returns an approved Flow ... a Flow not in the catalogue is rejected".
  The code has none.

RTDS 4.10.0 has a request and a Response for every SRV of the two Flows. Request body elements, read from the files:
6.2.9 `ReadDeviceConfigurationPaymentMode` (service reference `6.2`), 6.30 `ReadCHFSubGHzConfiguration` (`6.30`), 11.2 `ReadFirmwareVersion` (`11.2`).
Response elements: `ReadDeviceConfigurationPaymentModeRsp` (fact `PaymentMode`), `ReadFirmwareVersionRsp` (fact `FirmwareVersion`),
`ReadCHFSubGHzConfigurationRsp` (a channel list plus scalar thresholds).

## Goals / Non-Goals

**Goals:**

- The Flow workflow runs the steps of the named Flow in order, deciding after each Response, and publishes one `FlowStep` per step.
- Adding or changing a Flow touches the catalogue, not the workflow.
- The two draft Flows run end to end on the local cluster, with the existing simulator behaviours still working.

**Non-Goals:**

- A rule language for decisions. The decision after a step is one of two fixed rules (below).
- Reading the Flow content from DCC's files at run time, a catalogue editor or hot reload.
- Changing the Service Request workflow, the signing, sending or waiting, or the retry model.

## Decisions

### D1. The catalogue is a Temporal activity whose data is Java records in the `entity` package

`FlowCatalogueActivity.lookup(flow)` returns the Flow's steps, or a "not found" result. The data is plain records (`Flow`, `Step`) written in the
entity layer, and the activity adapter reads them. This follows the model (the catalogue is a Temporal activity).

- *Why an activity:* its result is recorded in the workflow history. A Flow that is mid-run when the catalogue changes replays against what it
  was given, not against new code. A lookup in the workflow body would break replay (non-determinism) the day a Flow changes.
- *Rejected:* a static lookup called straight from the workflow (replay risk, deviates from the model); a properties or YAML file read at start
  (more code to parse and validate, and the Flows are code-reviewed content for now); a database (the model has no database in the Orchestrator).

### D2. A step is an SRV, the device types it accepts, and the fixed rule "stop on failure"

`Step(srv, deviceTypes)`. After each step the Flow continues only if the outcome is `RESPONDED`. Any other status ends the Flow. `MessageSuccess="false"`
is not a failure here: the status is still `RESPONDED` (the existing rule "recorded, not judged" is unchanged), so the next step runs.

- *Why:* both draft Flows are plain sequences, and the only decision DCC's file states is not expressible without the hub chain (out of scope).
  A rule language now would be invented before the real rules are known.
- *Rejected:* conditions on Facts (nothing in DCC's file to base them on yet); per-step "continue on failure" flags (nothing needs it).

### D3. The Verdict is decided over the steps by a small rule

1. If at least one step is `RESPONDED`: `RESPONDS`.
2. Else if the first step ended `TIMED_OUT` or `FAILED_ALERT`: `NO_RESPONSE` (as today).
3. Else: `NOT_DIAGNOSED`.

So a device that answered step 1 and then went silent on step 2 is `RESPONDS`: it was reachable. Both steps are published, so a consumer sees what happened.
This is our proposal and open question 3 of the proposal. The rule is one method, easy to change.

- *Rejected:* "all steps must respond for RESPONDS" (would turn a reachable device into NO_RESPONSE because of one later failure, against the stated goal of
  confirming whether a device is really unreachable); deciding from the last step only.

### D4. Each step has its own Request ID, strictly increasing

The placeholder counter is the workflow time in milliseconds. With two steps in one Flow two Request IDs could collide if both read the same
millisecond, so the counter is `max(Workflow.currentTimeMillis(), previous + 1)`. The workflow ID of each child is derived from its Request ID as
before. Deterministic on replay. The counter rule is still the placeholder one (open point carried from `real-duis-messages`).

- *Rejected:* a global counter service (the model has none; "Request ID counter" is an open question with DCC).

### D5. Flow lookup and device type check happen before any request

If the catalogue has no such Flow, or the row's device type is not accepted by the Flow's first step, the Flow ends with `NOT_DIAGNOSED`, publishes the
`DeviceResult`, logs the reason (`event.action` `flow-refused`, with the Flow name and device type) and sends nothing. It does not fail the workflow,
so the Page does not count it as `FLOW_FAILED`. The other steps' device types are checked by the DUIS Signer as today (the signer refuses an SRV for a
device type it does not allow).

- *Rejected:* failing the workflow with an `ApplicationFailure` (it would count as `FLOW_FAILED`, which is for bugs, not for a bad row); silently falling back to the old SRV-by-device-type rule (hides the Manifest mistake).

### D6. One new Rsp reader per SRV, Facts stay a flat map of strings

`RealResponseDecoder` gets the three new SRVs.

| SRV | Rsp element | Facts |
|---|---|---|
| 6.2.9 | `ReadDeviceConfigurationPaymentModeRsp` | `MessageSuccess`, `PaymentMode` |
| 11.2 | `ReadFirmwareVersionRsp` | `MessageSuccess`, `FirmwareVersion` |
| 6.30 | `ReadCHFSubGHzConfigurationRsp` | `MessageSuccess` and every direct scalar child (the thresholds, `MaximumSubGHzChannelChangesPerWeek`, `GSMECurfew` ...) by element name. The channel lists are not Facts |

The existing rules for `MessageSuccess="false"` (`DebugSummary`, no register Facts) apply to all of them.

- *Why scalars only for 6.30:* the file does not say what triage needs from the channel lists; the scalars are cheap and carry no structure. This is open question 7 of the proposal.
- *Rejected:* flattening the channel lists into one Fact per channel (about 60 Facts per device with no stated use); storing the raw Response XML in the Fact map (Temporal histories stay small, as the existing rule says).

### D7. Request building and the signer's allowlist grow by table entries

`RequestBuilderActivityImpl` gets three rows (`6.2.9`: service reference `6.2`, body `ReadDeviceConfigurationPaymentMode`; `6.30`;
`11.2`). The signer's partial schema gets the three body elements, labelled as derived from RTDS. Allowlist placeholder values, role `other-user`:
`6.2.9` ESME,GSME; `6.30` CHF; `11.2` ESME,GSME,CHF. `registered-supplier-agent` is unchanged. [CONFIRM] with DCC.

### D8. The simulator answers success only

`DspMessages.response` gets the three new SRVs with the RTDS success values (for example `PaymentMode` Prepayment, `FirmwareVersion` 0123ABCD).
The existing behaviours A0 to A7 are unchanged and apply to every step of a Flow (a device behaviour is chosen by the device ID, not by the SRV).

### D9. Samples and names

Flow names: `hub-triage` (device types CHF) and `meter-triage` (ESME, GSME). The three sample Manifests are rewritten so each row names the Flow that
matches its device type. `device-types.csv` keeps one device of each type; `device-behaviours.csv` keeps the A0 to A7 devices on `meter-triage`;
`one-device.csv` uses `meter-triage`.

## Risks / Trade-offs

- **The two Flows are our reading of a list, not DCC's Flows** → they are named and documented as drafts, the catalogue is data, and the proposal lists what DCC must confirm.
- **A silent behaviour (A3) now takes the response timeout once and ends the Flow at step 1** → the same time as today; a device that answers step 1 and not step 2 is only seen with a new simulator behaviour, which is out of scope.
- **Behaviours apply per device, so every step of a device behaves the same** → acceptable for a first run; a per-step behaviour would need a richer simulator setting.
- **More Kafka events** (two `FlowStep` per device) → consumers are not in this repository; event IDs stay unique per step (`<flow workflow id>/step-N`).
- **6.30 Facts depend on a partial reading of one sample** → covered by the RTDS error and success variants in decoder tests; the open question stays open.
- **A catalogue change while Batches are running** → safe by D1 (history holds the steps), but a new Flow definition only applies to Flows started afterwards.

## Migration Plan

1. Add the catalogue, the three SRVs (builder, signer, decoder, simulator) and the Flow loop behind the existing Flow workflow name `Flow`.
2. Replace the placeholder Flow registration in `TemporalWorker`; rewrite the sample Manifests and docs.
3. Rollback: revert the commit. The workflow type name `Flow` and the `FlowInput` shape do not change, so a Temporal cluster with old histories needs no migration
   for the local setup (the local cluster is recreated by `deploy/edt.sh down` and `up`).

## Open Questions

- **Model element `edt.orchestrator.flowCatalogue`** (Flow Catalogue Activity): this design implements it as an activity over in-code data. Whether DCC's real catalogue
  is loaded from configuration or a file is not decided; D1 keeps that swappable.
- **Model element `edt.duisSigner.signActivity`**: the device type travels in its input next to the request (carried from `real-duis-messages`, still to confirm with the lead).
- The hub chain (11.2 only after an 8.9 that shows a recent last communication), 6.2.4 against 6.2.9, the Verdict rule over steps and the real allowlist per role: see `proposal.md`, Open questions 1 to 4 and 8.
