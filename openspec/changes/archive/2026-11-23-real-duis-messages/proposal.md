# Proposal

## Why

Every message the four EDT containers exchange with the DSP is in an invented format (`urn:edt:placeholder:duis`): the
Service Request the Orchestrator builds, the schema the DUIS Signer validates it against, the Response and DCC Alert the
Response Listener reads, and the Response the Orchestrator decodes. DCC has now released the Reference Test Data Set
(RTDS 4.10.0: DUIS 5.5 requests, MMC 5.5 responses and alerts, GBCS 4.3), so the real message shapes are known for the read
Service Requests the EDT sends. Running the containers against invented messages hides three things the real format
changes: a Response carries no Request ID (it is rebuilt from three header fields), a real request carries no device type
(the signer's policy check needs one), and a device that answers can still report `MessageSuccess="false"`.

## What Changes

- **The Service Request is built in the real DUIS 5.5 shape** (`ServiceUserGateway` namespace, `schemaVersion` 5.5, header with
  `RequestID`, `CommandVariant`, `ServiceReference` and `ServiceReferenceVariant`, one request element as the body) for the
  two Service Requests the placeholder Flow uses: SRV 4.1.1 (Read Instantaneous Import Registers) for meters, and SRV 8.9 (Read
  Device Log) for a Communications Hub, replacing the placeholder 8.2, for which RTDS has no Response.
- **The DUIS Signer validates the real structure.** The invented `service-request-placeholder.xsd` is replaced by a partial
  schema written from the RTDS examples, labelled as such, until DCC provides the DUIS XSD. The device type, which the real
  request does not carry, is passed to the signer next to the request so the policy check "device type allowed for the SRV"
  keeps working. The SRV allowlist is keyed to the real SRVs.
- **The Response Listener reads the real Response** (`GBCSResponse` with a `ResponseMessage`) and rebuilds the Request ID from
  `BusinessTargetID:BusinessOriginatorID:OriginatorCounter`, so the Service Request workflow is still signalled by its ID. For
  SRV 8.9 the Request ID comes from the supplementary remote party fields. A message that is a device alert, not a Response,
  is refused like any unreadable message.
- **The Orchestrator decodes the real Response into Facts** per SRV and device type, and records `MessageSuccess` and, when it
  is false, a short summary of the debug information. The outcome of the Service Request stays `RESPONDED`, as today.
- **The DSP simulator answers in the real Response shape** for 4.1.1 (electricity and gas) and 8.9, for the device type it is
  configured to know. The synchronous response and the DCC Alert stay in the placeholder format (see Open questions).
- Tests use small inline messages written from the RTDS examples. README and `HAPPY-PATH-MANUAL.md` are updated, and a sample
  Manifest with one device of each type is added.
- **BREAKING for anything that reads the Facts of a `FlowStep` event** (Kafka `edt.flow-steps`): the fact
  `InstantaneousImportRegister` no longer exists; the facts are the real register names. Nothing in this repository reads them.

## Capabilities

### New Capabilities

- `service-request`: the Service Request the EDT sends: its DUIS 5.5 shape, the Request ID, the SRVs of the placeholder Flow, and
  what the DUIS Signer checks before it signs (structure, SRV allowlist per User Role, originator, device type for the SRV).
- `dsp-response`: how a Response from the DSP is recognised, matched to its Service Request, and turned into Facts, including
  what is recorded when the device reports `MessageSuccess="false"`.

### Modified Capabilities

None. `structured-logging` and `local-cluster` do not change behaviour.

## Impact

- Code: `orchestrator` (`RequestBuilderActivityImpl`, `PlaceholderResponseDecoder` and its use case interface, `RequestId`,
  `PlaceholderFlowWorkflowImpl` for the SRV of a Communications Hub, `SignActivity` input), `duis-signer`
  (`XmlServiceRequestReader`, the schema resource, `SignActivityImpl`, `ServiceRequest`, allowlist configuration),
  `response-listener` (`XmlDspMessageReader`, the Request ID rule), `dcc-tools/dsp-simulator` (`DspMessages`, a device type setting).
- Interfaces: the Temporal `SignActivity` between the Orchestrator and the DUIS Signer gains the device type in its input.
  The model element is `edt.duisSigner.signActivity`; its description does not say where the device type comes from (open question).
- Configuration: the signer's `edt.policy.*` allowlist (8.2 becomes 8.9); a device type setting for the simulator in Compose and the Helm chart.
- Docs and samples: README, `HAPPY-PATH-MANUAL.md`, `samples/manifests/`.
- Out of scope, so not changed: the Flow Catalogue and any multi-step Flow; the Verdict rules, including what
  `MessageSuccess="false"` should mean for a Verdict; the Manifest format; decoding GBCS hexadecimal payloads (Parsec); future-dated
  and critical Service Requests; device alerts; the real DUIS XSD; the synchronous response and the DCC Alert formats; the Request ID
  counter; committing the RTDS files to the repository.

## Open questions

1. **Licence:** may RTDS files be committed to this repository? Until it is answered, only small inline messages written from them are used. (RTDS is untracked in git.)
2. **DUIS XSD:** the real schema is not in RTDS. The partial schema is ours, derived from the examples, and must be replaced when DCC provides it.
3. **Synchronous response** (`SyncResponse`, codes I0 and E99) and **DCC Alerts N12, N13, N53:** RTDS has no samples. They stay placeholder, so the Response Listener reads two namespaces for now. [CONFIRM] with DCC.
4. **What exactly reaches the Response Listener and the Orchestrator? RESOLVED 2026-11-23: the decoded MMC XML (gate 2.1).** The model says the Response
   Decoder decodes "the GBCS payload of each Response with Parsec" and that the Response Listener's DUIS Library reads "the Request ID
   and codes of each message". RTDS holds the MMC XML (what Parsec or Parse & Correlate yields from the GBCS payload) and the GBCS
   hexadecimal, but not the DUIS Response that would carry the payload and the Request ID to a user. This change assumes the MMC XML
   is what arrives and that its header rebuilds the Request ID. That is an assumption, not the model's wording, and it is only valid
   if confirmed. The Request side does not depend on it. [CONFIRM] with the lead before the Response-side tasks (tasks.md group 2 is the gate).
5. **Device type to the signer:** the real request has no device type. Passing it in the activity input is our choice; the model says only that the check exists. Confirm that adding it to `edt.duisSigner.signActivity`'s input does not deviate from the model.
6. **SRV 8.9 correlation** rests on one sample (the Response header has `SupplementaryRemotePartyID` and `SupplementaryRemotePartyCounter`). [CONFIRM] it holds for every request sent through the Communications Hub.
7. **`MessageSuccess="false"`:** a device that answers with a hardware fault is currently `RESPONDS`. What it should be is a Verdict question for DCC's Flows. This change only records the value.
8. **Request ID counter:** RTDS counters start at 1000 and rise; ours is the workflow time in milliseconds (`RequestId.placeholder`). Unchanged and still [CONFIRM].
9. **Which Service Request a real Flow sends to a Communications Hub** is DCC's Flow content. 8.9 is used here only because RTDS has a Response for it.
