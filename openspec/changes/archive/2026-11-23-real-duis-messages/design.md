# Design

## Context

The placeholder format (`urn:edt:placeholder:duis`) is written or read in five places, each behind an interface, so the
swap touches adapters only (Clean Architecture): `RequestBuilderActivityImpl` (Orchestrator), `XmlServiceRequestReader` with
`service-request-placeholder.xsd` (DUIS Signer), `XmlDspMessageReader` (Response Listener), `PlaceholderResponseDecoder`
(Orchestrator, behind the `ResponseDecoder` port) and `DspMessages` (the DSP simulator in `dcc-tools`). See proposal.md for why.

What the released examples (RTDS 4.10.0, DUIS 5.5, MMC 5.5) show, read directly from the files:

- A Request is `sr:Request` in `http://www.dccinterface.co.uk/ServiceUserGateway`, with `Header` (`RequestID`, `CommandVariant`,
  `ServiceReference`, `ServiceReferenceVariant`), a `Body` with one element, and an enveloped `ds:Signature`. It has no
  device type and no originator element: both are inside `RequestID` (`originator:device:counter`).
- A Response is `ra:GBCSResponse` in `http://www.dccinterface.co.uk/ResponseAndAlert`. It has no Request ID; its header has
  `BusinessOriginatorID` (the device), `BusinessTargetID` (our originator) and `OriginatorCounter`, which rebuild it. The SRV 8.9
  Response goes through the Communications Hub and also has `SupplementaryRemotePartyID` and `SupplementaryRemotePartyCounter`, which
  hold the original originator and counter.
- The outcome is `MessageSuccess` on the `...Rsp` element. On failure `DebugInfo` explains it: `COSEMDebug` for electricity
  (`hardware-fault`), `ZIGBEEDebug` for gas (`HARDWARE_FAILURE`).
- The existing signer already signs as the examples do (enveloped, exclusive canonicalisation, ECDSA with SHA-256).

What the examples do not show, and the model says: the Response Decoder Activity "decodes the GBCS payload of each Response with
Parsec", and the Response Listener's DUIS Library "reads the Request ID and codes of each message". RTDS has the MMC XML, which is
what Parsec or Parse & Correlate yields, and the GBCS hexadecimal, but no DUIS Response that carries the payload and the Request
ID to a user (see D0).

Constraints: follow the model (nothing here changes a container, relation or flow), no new library, signing only through JCA
(unchanged), and the open points in proposal.md are not decided here.

## Goals / Non-Goals

**Goals:**
- Every message between the containers and the DSP simulator that RTDS covers is in the real shape, end to end, for one
  electricity meter, one gas meter and one Communications Hub.
- Adding the next Service Request is one table entry per component, not a rewrite.
- The tests prove the shapes with inline messages written from the examples.

**Non-Goals:**
- The Flow Catalogue, multi-step Flows, Verdict rules, GBCS hexadecimal decoding, device alerts, critical or future-dated
  requests, the Manifest format, committing RTDS files, and the real DUIS XSD (see proposal.md).
- Changing the synchronous response, DCC Alerts, or the Request ID counter.

## Decisions

### D0. Confirmed: the MMC XML is what reaches the Response Listener and the Orchestrator
**Answer (2026-11-23, confirmed by the user at gate 2.1):** the DSP delivers the decoded MMC XML. Groups 3 to 5 go ahead as planned.
(The lead's name and the date of the original answer are not recorded; if this was relayed, note the source.) The text below is the
original assumption, kept for the reasoning.

The Request side (D1, D2, D3, D8) rests on released DUIS requests and is independent of this. The Response side (D4, D6, D7)
treats the MMC XML as the Response: the simulator delivers it, the Listener rebuilds the Request ID from its header, and the decoder
reads Facts from it. The decoder stays behind the `ResponseDecoder` port, so Parsec can replace it, and the MMC XML then stands for
Parsec's output.
This is an assumption. The model's wording points to a DUIS Response carrying a GBCS payload. If that is the case, D6 (where the Request
ID comes from) and D7 (what the simulator delivers) change, and D4 changes its input; D1 to D3 and D8 do not. The tasks therefore put a
gate (tasks.md group 2) before the Response side.
Alternative rejected: waiting before any work. The Request side can proceed, and it needs the simulator to read the new request.

### D1. The Request Builder writes the real shape from a small SRV table
`RequestBuilderActivityImpl` keeps producing a string, now the DUIS 5.5 `Request`, from a table: SRV to (`ServiceReference`, body
element). The table has two rows: 4.1.1 to (4.1, `ReadInstantaneousImportRegisters`) and 8.9 to (8.9, `ReadDeviceLog`). An SRV
not in the table fails the build with a reason. `CommandVariant` is 1 for both, a non-critical read in the released data.
Alternative rejected: generated classes from the DUIS XSD (the XSD is not available, and the model's DUIS Library is not ours yet).

### D2. A partial schema in the real namespace replaces the placeholder schema
The signer validates against a hand-written XSD for `sr:Request` whose `Body` is a choice of the two request elements, with
`schemaVersion` fixed to 5.5, and a comment saying it is derived from released examples and is not the DUIS schema.
Alternatives rejected: no schema check (the model's Signing Policy Check lists it as the first check); a Body of `xs:any`
(it would accept anything, so the check would prove nothing). Cost: each new SRV needs one line in the schema.

### D3. The device type is a second argument of the Sign activity; the originator is the first part of the Request ID
`SignActivity.sign(unsignedServiceRequest)` becomes `sign(unsignedServiceRequest, deviceType)`; both sides keep their own copy of
the interface, and the activity name `Sign` is unchanged. The reader takes the request ID, the SRV and the originator from the
XML (the originator is the part of `RequestID` before the first colon) and the device type from the argument.
Alternatives rejected: deriving the type from the device ID (no rule known); adding a non-DUIS element to the request (it would
no longer be a real request and would be inside the signed bytes); dropping the check (the model lists it).

### D4. The decoder keeps its port and reads the device type from the content
`ResponseDecoder.decode(srv, responseXml)` is unchanged. A decoder per SRV, in a map keyed by SRV, reads the element it needs:
for 4.1.1 the `Electricity` or `Gas` child tells the device type, and for 8.9 the device log. Facts are written as flat
`name -> text`:

| SRV, content | Facts |
|---|---|
| 4.1.1, `Electricity` | `ActiveImportRegister`, `ReactiveImportRegister`, and `SecondaryActiveImportRegister` when present, each with `<name>.Unit` |
| 4.1.1, `Gas` | `ConsumptionRegister` and `ConsumptionRegister.Unit` |
| 8.9 | `CHFDeviceLog.<deviceId>.LastCommunicationsDateTime` and `CHFDeviceLog.<deviceId>.SubGHzLinkQuality` per entry |
| any | `MessageSuccess`; and `DebugSummary` when it is `false` |

`DebugSummary` is the distinct result strings in the debug information that are not `success`, joined with a comma. An SRV with
no decoder, or a Response without its `...Rsp` element, is `UndecodableResponseException`, so the workflow ends `DECODE_FAILED`
as now. A value the Response does not carry (an empty `Electricity` on failure) produces no Fact.
Alternatives rejected: keeping the placeholder's "every simple Body element is a Fact" (repeated and nested elements such as
`LogEntry` would collide or lose their unit); generated classes (no XSD).

### D5. `MessageSuccess` is recorded, not judged
The Facts carry it and the Service Request stays `RESPONDED`. Alternative rejected: a new outcome status for
`MessageSuccess="false"`, which would force a Verdict rule that DCC's Flows must define. Today `RESPONDED` maps to `RESPONDS`
in the placeholder Flow; this change does not alter that, and makes the value visible in the `FlowStep` event.

### D6. The Response Listener rebuilds the Request ID in its reader
`XmlDspMessageReader` accepts two kinds of message: a `GBCSResponse` whose Body holds a `ResponseMessage` (real, see D0), and the
placeholder `DCCAlert` (until DCC gives samples). It rebuilds the Request ID from the header (the rule in the `dsp-response` spec,
with the supplementary variant when `SupplementaryRemotePartyID` is present). `DspMessage` and the Workflow Signaller do not change. A
`GBCSResponse` whose Body holds a `DeviceAlertMessage`, a placeholder `Response`, or anything else is unreadable.
Alternative rejected: also accepting the placeholder `Response`, which would keep tests passing on invented messages.

### D7. The simulator answers in the real shape and learns the device type from configuration
`DspMessages` reads the request ID and SRV from the real request (the device is the middle part of the Request ID) and writes:
4.1.1 for an electricity meter (the default) or a gas meter (device IDs listed in a new setting), and 8.9 as a device log with
two entries. The header is built from the request: `BusinessOriginatorID` is the device, `BusinessTargetID` the originator, the
counter is the request's; for 8.9 the target is the Access Control Broker and the supplementary fields carry the original
originator and counter, as in the sample. The behaviours chosen by the last group of the device ID (A0 to A7) are unchanged. The
tampered Response now has `MessageSuccess="true"` changed to `"false"` after signing; the tampered synchronous response and the
DCC Alerts stay placeholder.
Alternatives rejected: encoding the type in the device ID (it would collide with the A0 to A7 codes); replaying the RTDS files
(the Request ID and counter are fixed in them, and the licence is open).

### D8. The placeholder Flow and the allowlist follow the SRVs
`PlaceholderFlowWorkflowImpl` sends 8.9 instead of 8.2 to a CHF. The signer allowlist drops `8.2` and adds `8.9=CHF` for the Other
User role. Alternative rejected: keeping 8.2, for which RTDS has no Response, so the simulator would have to invent one.

### D9. Tests use inline messages written from the examples
Each test holds a short message with the same structure and values as the RTDS example it comes from, and names the example in
a comment. Alternative rejected: reading `samples/rtds_samples` in the tests (not committed, so a clean checkout would fail, and the
licence is open).

## Risks / Trade-offs

- [The partial schema rejects a valid request for an SRV it does not list] -> Intended for now; a test fails if the schema accepts an
  SRV that is on no allowlist (the reverse is allowed: the placeholder `6.15.1` of the Registered Supplier Agent is only on the allowlist).
- [The Response side rests on D0] -> The gate before it; if the answer differs, update this change before building groups 3 to 5.
- [The 8.9 correlation rests on one sample] -> The supplementary rule is tagged [CONFIRM] in the spec; a Response with no
  match times out as before, so a wrong rule shows up as `TIMED_OUT`, not as a wrong answer.
- [`MessageSuccess="false"` is still `RESPONDS`] -> Unchanged on purpose; the Fact is in Kafka for whoever decides.
- [The Sign activity changes its arguments] -> Deploy the Orchestrator and the DUIS Signer together; on the local cluster `edt.sh up`
  reinstalls both. Workflows in flight at the upgrade should be finished or terminated first.
- [The Facts names change] -> `edt.flow-steps` consumers must be told; none exists in this repository.
- [The DSP's own signature on a real Response is unknown] -> The simulator keeps signing as now; the check is unchanged and
  [CONFIRM] with DCC.
- [The signer puts the certificate in `KeyInfo` as `X509Data`, while the examples use an issuer and serial number] -> Unchanged;
  [CONFIRM] what the DSP requires.

## Migration Plan

No persistent data holds these messages. Order: the simulator and the Response Listener first (they must read and write the new
Response), then the Orchestrator and the DUIS Signer together, then Compose and the Helm chart settings. Run `edt.sh down` and `up`
on the local cluster, then the one-device and three-type Manifests. Rollback is reverting the commit and reinstalling.

## Open Questions

- Model elements affected: `edt.orchestrator.requestBuilder`, `edt.orchestrator.responseDecoder` (Response Decoder Activity),
  `edt.duisSigner.signActivity` and `edt.duisSigner.signingPolicy`, and the Response Listener with its Workflow Signaller.
- Does adding the device type to the Sign activity's input deviate from the model? (proposal.md, open question 5)
- Are the Fact names right for DCC's Flows? They are ours; DCC's Flows will say.
- What does the DSP deliver, the decoded Response or a DUIS Response with a GBCS payload for Parsec? (proposal open question 4, D0)
  This changes the Response-side tasks, so it is a gate (tasks.md group 2), not a deferrable question.
