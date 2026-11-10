# Enhanced Diagnostics Tool (EDT) — project context

> How this file was written: from a recorded call with a colleague (the transcript was noisy,
> so some terms were inferred) and from a LikeC4 model published as a static site. The model is
> saved in this repo as `docs/architecture-model.md`, a read-only snapshot. It is not the editable
> `.c4` source. If `.c4` files are added to the repo, they replace the snapshot as the architecture
> source of truth. If this file, the snapshot or the code disagree, report the mismatch instead of picking a side.
> Items marked **[CONFIRM]** are unverified.

## Purpose

The DSP (DCC Data Service Provider) has many smart-meter devices (meters and communications hubs)
that do not respond, or appear not to. The EDT (Enhanced Diagnostics Tool) lets the DCC (Data Communications
Company) act as a user of the smart metering network. For each device in a signed Manifest (bulk file) it runs one
of 3 approved Flows of non-critical DUIS Service Requests, one after the other, and publishes the outcome of each
device and of each Batch. The goal is to confirm that a device is really unreachable, or find that it responds and is
simply reported wrongly. People use the results to decide an action, for example sending someone to the property.
It is built on Temporal, which DCC provides.

## Glossary

- **DCC**: Data Communications Company, the client. DCC EIT (Enterprise IT) provides and operates the platform.
- **DSP**: DCC Data Service Provider. We talk to devices only through it.
- **DUIS**: DCC User Interface Specification. The XML format of Service Requests, Responses and DCC Alerts.
- **Service Request**: one signed DUIS XML request to a device (or its Communications Hub). It has a Request ID.
- **Response / DCC Alert**: the answer from the device, or an alert from the DSP (for example N12, N13, N53).
- **Manifest**: the signed bulk file of devices, uploaded to S3. **Batch**: the processing of one Manifest.
  **Flow**: the approved sequence of Service Requests run on one device. **Verdict**: the outcome of a Flow. **Facts**: values taken from a Response that a Flow decides on.
- **User Roles**: the two DUIS roles the EDT sends as, Other User and Registered Supplier Agent. Each has its own key and certificate.
- **SMKI / DCCKI / GBCS**: smart-metering key infrastructure / DCC key infrastructure (used for TLS to the DSP) / the device message payload specification.
- **Comms hub / meter**: low-level battery-powered devices at customer premises. They save power, so they are not always listening.
- **mTLS**: both sides verify each other's certificate. A wrong client certificate gets silence.
- **HSM / FIPS 140**: key protection where keys never leave the module and signing happens inside it.

## Architecture

Full detail is in `docs/architecture-model.md`. Summary:

Four containers are built by us (Java 25, Quarkus, Temporal Java SDK):

| Container | Role |
|---|---|
| Batch Ingest | Takes the Manifest events from SQS, verifies the Manifest signature (KMS Verify) before anything else, starts one Batch workflow per valid Manifest. Workflow ID derived from bucket, key and object version, so a duplicate message starts one Batch. Stateless. |
| Orchestrator | Temporal worker running the Batch, Page, Flow and Service Request workflows. Reads Manifest rows by S3 byte range. Sends each signed Service Request through the gateway, decodes Responses (Parsec, existing CSW software) and publishes to Kafka. Never holds a signing key. |
| DUIS Signer | The only container that can sign. A Temporal worker on its own rate-limited task queue: checks the request against the DUIS schema and the allowlist of each User Role, chooses the role, signs through KMS and writes an audit event for every decision. |
| Response Listener | The DCC Adapter's listener, reused and adapted. Receives Responses and DCC Alerts, checks their DSP signature, reads the Request ID and signals the waiting Service Request workflow. The Workflow Signaller component is new and replaces the adapter's database. |

Provided and operated by DCC (not ours): Manifest Bucket (S3), Manifest Events (EventBridge + SQS with a dead-letter queue),
Temporal Server and UI, AWS KMS, certificate store (PKCS#12, mounted read-only), Kafka, DSP HTTPS Gateway, log agent,
CloudWatch Logs, monitoring and the identity provider for the Temporal UI.

### Main flow

1. A DS&A operator uploads a Manifest signed by DCC to the S3 bucket. Re-uploading starts a new Batch.
2. S3 emits an event (EventBridge, then SQS). Batch Ingest verifies the signature and starts the Batch workflow.
3. The Batch workflow splits the Manifest into Pages of rows and keeps a configurable number of Pages running. It continues as new after a number of Pages to keep its history small.
4. Each Page workflow keeps at most a configured number of device Flows running. A Flow workflow runs one approved Flow from the Flow catalogue and decides after each Response whether to continue or stop.
5. Each Service Request has its own workflow, whose ID is derived from the DUIS Request ID. It builds the request, has the DUIS Signer sign it through Temporal, and sends it. The send activity runs on a shared task queue with a global rate limit (and fairness between Batches), and posts once with no retry of its own.
6. The DSP HTTPS Gateway sends it to the DSP over DCCKI TLS (mTLS) and retries transient errors. The synchronous DUIS Response comes back to the Orchestrator.
7. The real answer arrives later and asynchronously, possibly hours later. The gateway passes it over HTTP to the Response Listener, which signals the Service Request workflow whose ID matches the Request ID. It talks to the Temporal Server, not to the Orchestrator.
8. The Service Request workflow waits up to a configurable timeout. A DCC Alert N12 or N13, or the timeout, marks it failed. It is never sent again.
9. The Orchestrator publishes to Kafka (producer only, keyed by device): an event for each step of each Flow, the result of each device Flow and the result of each Batch. Each event carries an ID so consumers can drop duplicates.

### Why it is asynchronous

Devices are battery-powered and may not be reachable when the request is sent. A response can take minutes or hours, or
never arrive. Temporal provides the durable waiting and recovery.

### Operations

Every container writes local ECS JSON logs, which the DCC log agent sends to CloudWatch. Every container exposes health
and Prometheus metrics endpoints (JVM and Temporal SDK), scraped by DCC monitoring. The DUIS Signer writes audit events.

### Flow content (not final)

The 3 approved Flows, their Service Requests, order and decisions are provided by DCC before the project starts, as are the
Service Request allowlists per User Role. A draft from the call included: check whether the DSP thinks the meter belongs
to that comms hub, then payment mode, device status, communication channels and settings, battery state, power supply,
firmware version and coverage availability. **[CONFIRM against DCC's final Flows]**

## Technical decisions so far

- Java 25, plain Quarkus and the Temporal Java SDK, with no Quarkus–Temporal integration library.
- Clean Architecture: entities, use cases, interface adapters, frameworks/infrastructure. Dependencies point inward only.
  Use interfaces for databases and external systems, wired with Quarkus dependency injection.
- Structure preference: one module per application with structured packages, plus an **architecture test**
  that fails if a package imports from a forbidden layer. This replaces splitting every layer into its own module.
- Logging: structured logs (key-value fields, not free text) in ECS JSON.
- Every container exposes health and Prometheus metrics endpoints.
- We write the Kubernetes scripts for a minimal setup that lets us run tests (not the final production setup).
- Crypto: a software provider for tests and the HSM-backed provider in production, selected by configuration, not by code changes.
- Stateless containers that scale out horizontally. State lives in Temporal.

## Open points and mismatches (do not decide these silently)

1. **KMS vs CloudHSM.** The model says AWS KMS (FIPS 140-3 level 3) for signing and Manifest verification. In the call, the lead said CloudHSM was recently being discussed. Batch Ingest also uses KMS (Verify), so a change touches more than the signer.
2. **Retries.** The model says the send activity does not retry, the gateway retries transient errors, and a Service Request that failed or timed out is never sent again. In the call, the lead described application-level retries through Temporal that recreate the whole Service Request including its counter. Which is current?
3. **Kafka.** The model publishes step events as well as device and Batch results. The call mentioned device level and probably file level.
4. Manifest signature format on the S3 object (for example an object tag) is "to be agreed with DCC". Kafka authentication by mutual TLS is assumed, to be confirmed with DCC.
5. The call said the signing provider might need a vendor library installed (HSM case). The model describes KMS API calls instead. Related to point 1.

Resolved in the model, but worth confirming with the lead: response correlation uses a workflow ID derived from the DUIS Request ID, so the Response Listener needs no table of its own.

## Background notes for learning

### Java Cryptography Architecture (JCA)

- Engine classes (`Signature`, `MessageDigest`, `Cipher`, `Mac`, `KeyStore`, `SecureRandom`) are requested with
  `getInstance(algorithm)`. The JVM searches installed providers in preference order and the first match wins.
  `getInstance(algorithm, providerName)` forces a specific provider.
- Behind each engine class is an abstract SPI class (for example `SignatureSpi`) implemented by the provider.
- `Security.getProviders()`, `addProvider()`, `insertProviderAt()` and `removeProvider()` manage providers. Static
  registration uses `security.provider.n` in the `java.security` file.
- General code should not hard-code a provider. Name one only when a specific implementation is required.
- `KeyStore` type `pkcs11` gives access to hardware modules. Keys are opaque `Key` objects.
- A `Signature` object is modal: `initSign(privateKey)`, `update(...)`, `sign()`.

### Temporal

- Workflows hold the durable logic. Activities do the side effects (HTTP calls, signing, Kafka, S3). Signals wake a
  workflow that waits for an external event, such as an asynchronous device response.
- Continue-as-new keeps a long-running workflow's history small. Task queue rate limits and fairness control throughput.

## How to work in this repository

- **Explore before changing.** Do not write or modify code unless I ask. Prefer reading and reporting.
- Cite file paths. Separate what you verified in the repo from what you infer.
- Treat `docs/architecture-model.md` (or `.c4` files, if present) as the architecture reference. Flag any mismatch with the code or with this file.
- If the repository defines a team workflow for AI-assisted development (an agent flow with gates, review steps and
  a retrospective, started from a Jira ticket), follow it. Use it for complex work and new builds, not for tiny fixes.
- Explain concepts briefly (Temporal, JCA, Clean Architecture) when they matter for what you find. I am learning this domain.
- When something is unclear or conflicts with the open points above, ask me instead of assuming.
