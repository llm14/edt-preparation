# Happy path, step by step, with the debugger (view 100.010)

Goal: follow ONE device from the Manifest upload to the Kafka messages, stopping at each arrow of view 100.010.
Line numbers are from the code at the time of writing (branch `test-and-experimentation`); if a line moved, search for the method name.
Everything marked "unverified" was not run, only read.

## 0. Before you start

| Check | How |
|---|---|
| dcc-tools running | `docker compose ps` in `dcc-tools/`: floci, kafka, temporal, postgresql, dsp-simulator healthy |
| Simulator delivers to your machine | `docker inspect dcc-tools-dsp-simulator-1` shows `EDT_RESPONSE_LISTENER_URL=http://host.docker.internal:8084/...` |
| No app containers | `docker ps` shows no `edt-preparation-*` |
| Four apps running in IntelliJ | ports 8081 to 8084 answer `/q/health/ready`; debug ports 5005 to 5008 attached |
| Temporal UI | http://localhost:8088, namespace `edt` |

One-device Manifest (A0: the simulator answers after 5 s):
```bash
printf 'device_id,device_type,flow\n00-DB-12-34-56-78-90-A0,ESME,flow-1\n' > samples/manifests/one-device.csv
```

Two rules while debugging:
- Breakpoints: right-click the red dot, **Suspend: Thread** (not All), so Temporal's polling threads keep running.
- Clocks keep ticking while you are paused: an activity has 30 s; `DuisSenderActivityImpl.send` has ONE attempt (pause there more than 30 s and the request fails `SEND_FAILED`);
  the simulator gives up delivering a Response after about 15 s (so do not stay paused in the Response Listener for long). Step 4 of "break it" below shows this on purpose.

Workflow code (classes ending in `WorkflowImpl`) is REPLAYED: the same breakpoint is hit again each time Temporal rebuilds the state.
To stop only on the first, real execution, add this condition to the breakpoint (unverified): `!io.temporal.workflow.Workflow.isReplaying()`.
Activity code (`*ActivityImpl`) runs once per attempt.

## 1. The map: model arrow, breakpoint, what to look at

Order is the order in which the breakpoints are hit. App = the IntelliJ run/attach configuration.

### Step 1: DS&A Operator → Manifest Bucket, Manifest Bucket → Manifest Events

**Purpose.** This is how work enters the system, and it happens entirely outside the EDT. A DS&A operator (DCC staff) uploads a Manifest, the list of devices to diagnose, to an S3 bucket. DCC signs the Manifest first, and the signature travels with the object. The bucket keeps versions, so uploading the same file again creates a new version and therefore a new Batch. S3 then announces the new object with an "Object Created" event, which goes through EventBridge into an SQS queue (with a dead-letter queue for messages that keep failing). The queue is the only thing the EDT sees. The operator never calls the EDT directly, and the EDT never has to be running at upload time: the message waits.

No breakpoint (it is dcc-tools). Run the upload (from `dcc-tools/`), best with `batch-ingest` stopped or paused so the message stays on the queue:
```bash
MSYS_NO_PATHCONV=1 docker compose run --rm manifest-upload /manifests/one-device.csv
```
Look at: the S3 object version and its `dcc-signature` tag, and the SQS message (bucket, key, version-id).

### Step 2: Batch Ingest ← Manifest Events (take the message)

**Purpose.** Batch Ingest is the EDT's front door. Its one job is to take Manifest events off the queue, one at a time, and decide what to do with each. Using a queue instead of a direct call means no upload is lost if Batch Ingest is down or busy: the message stays until it is handled. A message is deleted only after it has an outcome. If something fails (S3, KMS or Temporal unreachable), it is left on the queue and delivered again, and after enough failures it ends on DCC's dead-letter queue. Batch Ingest keeps no state of its own, so more copies of it can run side by side.

| # | App | File:line | What to inspect |
|---|---|---|---|
| 1 | batch-ingest | `adapter/SqsManifestEventConsumer.java:46` (`receiveMessage`) | long poll, 20 s wait; `messages` after the call |
| 2 | batch-ingest | `adapter/SqsManifestEventConsumer.java:54` (`handle`) | `message.body()`: the "Object Created" event |
| 3 | batch-ingest | `adapter/SqsManifestEventConsumer.java:75` (`ingestManifest.ingest`) | `manifest` (a `ManifestRef`: bucket, key, versionId) |

### Step 3: Batch Ingest → Manifest Bucket and AWS KMS (verify the signature)

**Purpose.** This is the security gate, and it comes before anything else. The EDT acts as a user of the smart metering network, so a forged or altered Manifest could make it send requests to devices nobody authorised. Batch Ingest therefore proves that DCC signed exactly these bytes. It reads the signature from the object's tag and streams the file from S3, hashing it as it goes. It then asks AWS KMS to verify the signature against DCC's public key. Only the small hash goes to KMS, never the file, and the Manifest is never loaded whole into memory. If the signature is wrong, the Manifest is rejected, a security event is logged, and nothing starts.

| # | App | File:line | What to inspect |
|---|---|---|---|
| 4 | batch-ingest | `usecase/IngestManifest.java:25` (`verifier.verify`) | rule: verify BEFORE anything else |
| 5 | batch-ingest | `adapter/S3ManifestVerifier.java:44` (`getObjectTagging`) | the base64 signature from the tag |
| 6 | batch-ingest | `adapter/S3ManifestVerifier.java:61` (`s3.getObject`) | the object is streamed, not loaded |
| 7 | batch-ingest | `adapter/kms/KmsVerifySignatureSpi.java:60` (`engineUpdate`) | bytes hashed locally with SHA-256, chunk by chunk |
| 8 | batch-ingest | `adapter/kms/KmsVerifySignatureSpi.java:79` (`kms.verify`) | only the 32-byte DIGEST and the signature go to KMS |
| 9 | batch-ingest | `adapter/S3ManifestVerifier.java:67` (`signature.verify`) | the verdict |

### Step 4: Batch Ingest → Temporal Server (start the Batch workflow)

**Purpose.** Once the Manifest is trusted, Batch Ingest hands it over and steps out. It does no processing: it starts one Batch workflow in Temporal, giving it only the location of the Manifest (bucket, key, version). The workflow ID is built from those three values. If the same message arrives twice, the second start fails with "already started" and no second Batch is created. That is what gives "exactly one Batch per upload" even though the queue may deliver a message more than once. From this point the state of the Batch lives in Temporal, not in Batch Ingest.

| # | App | File:line | What to inspect |
|---|---|---|---|
| 10 | batch-ingest | `adapter/TemporalBatchStarter.java:33` (`start`) | workflow ID built from bucket, key, version |
| 11 | batch-ingest | `adapter/TemporalBatchStarter.java:40` (`stub.start`) | `BatchInput`; a second start with the same ID is "already started" |
| 12 | batch-ingest | `adapter/SqsManifestEventConsumer.java:85` (`case Outcome.Started`) then `:96` (`deleteMessage`) | the message is deleted only now |

Look in the Temporal UI: workflow `batch-edt-manifests/one-device.csv/<version>`, status Running.

### Step 5: Orchestrator ← Temporal Server (runs the Batch) and → Manifest Bucket (rows by byte range)

**Purpose.** This is where one Manifest becomes many pieces of work, in a shape that stays manageable at any size. The Orchestrator is a Temporal worker: it asks the Temporal Server for tasks and runs the workflow code. The Batch workflow splits the Manifest into Pages of rows and keeps a set number of Pages running. Each Page reads only its own rows from S3 with a byte-range read, and then starts one Flow per row, with a limit on how many run at once. Pages exist for two reasons: to cap how many devices are processed at the same time, and to keep each workflow's history small enough for Temporal (the Batch even "continues as new" after some Pages). Each Flow is the diagnosis of one device.

| # | App | File:line | What to inspect |
|---|---|---|---|
| 13 | orchestrator | `adapter/BatchWorkflowImpl.java:45` (`processManifest`) | `input`; the settings (rows-per-page 2, pages-at-a-time 2) |
| 14 | orchestrator | `adapter/BatchWorkflowImpl.java:84` (`startPage`) then `:99` (`Async.function(child::processPage ...)`) | the Batch starts a child Page workflow |
| 15 | orchestrator | `adapter/PageWorkflowImpl.java:45` (`reader.readPage`) | the Page calls an ACTIVITY to read the rows |
| 16 | orchestrator | `adapter/ManifestReaderActivityImpl.java:29` (`readPage`) | `request.offset()`, `request.rows()` |
| 17 | orchestrator | `adapter/S3ManifestBytes.java:23` (`getObjectAsBytes`) | the `Range: bytes=...` header (line 27) |
| 18 | orchestrator | `adapter/PageWorkflowImpl.java:50` (`pageRead` signal) | the Page tells the Batch where the next Page starts (the inferred signal, not in the model) |
| 19 | orchestrator | `adapter/PageWorkflowImpl.java:62` (`startFlow`) | one Flow per row |
| 20 | orchestrator | `adapter/PlaceholderFlowWorkflowImpl.java:33` (`diagnoseDevice`) | `srv` chosen from the device type; the Request ID |

### Step 6: Service Request workflow: build, sign

**Purpose.** A Flow talks to a device through Service Requests, and each Service Request has its own workflow. The Orchestrator builds the request but never holds a signing key. It asks the DUIS Signer to sign it, and the request travels to the Signer through Temporal on a separate task queue, so the two apps never call each other directly. The Signer is the only place in the system that can sign. It checks the request against the DUIS schema and against the allowlist of each User Role, picks the role, signs through KMS (the private key never leaves KMS), and writes an audit event for every decision, signed or refused. Keeping signing in one small, rate-limited, audited container limits what a mistake or an attack in the Orchestrator could do.

| # | App | File:line | What to inspect |
|---|---|---|---|
| 21 | orchestrator | `adapter/ServiceRequestWorkflowImpl.java:80` (`executeServiceRequest`) | workflow ID = `service-request-<RequestId>` (UI) |
| 22 | orchestrator | `adapter/RequestBuilderActivityImpl.java:10` (`build`) | the unsigned XML (activity on the `orchestrator` queue) |
| 23 | orchestrator | `adapter/ServiceRequestWorkflowImpl.java:85` (`signer.sign`) | this call goes OUT through Temporal to the `duis-signing` queue |
| 24 | duis-signer | `adapter/SignActivityImpl.java:18` (`sign`) | the activity arrives in a different process |
| 25 | duis-signer | `usecase/SignServiceRequest.java:40` (`reader.read`) | XML parsed (no DTD) and validated against the schema |
| 26 | duis-signer | `entity/SigningPolicy.java:44` (`decide`) | allowlist per User Role; returns the role or a refusal |
| 27 | duis-signer | `usecase/SignServiceRequest.java:48` (`signer.sign`) | goes to `JcaXmlSigner`, then the JDK XML DSig |
| 28 | duis-signer | `adapter/kms/KmsSignatureSpi.java:65` (`engineUpdate`) then `:82` (`kms.sign`) | SHA-256 locally, digest to KMS, private key never leaves KMS |
| 29 | duis-signer | `usecase/SignServiceRequest.java:49` (`auditLog.record`), `adapter/EcsAuditLog.java:19` | one audit event per decision (`edt.audit` logger) |
| 30 | orchestrator | `adapter/ServiceRequestWorkflowImpl.java:85` (after the line) | `signed.status()`, `signed.signedServiceRequest()` |

### Step 7: Orchestrator → DSP HTTPS Gateway (send once, check the answer)

**Purpose.** The signed request leaves the EDT through the DSP HTTPS Gateway, which carries it to the DSP over mutual TLS and retries transient network errors. The EDT itself posts once and never retries: a Service Request that failed or timed out is never sent again, so a command to a real device is not repeated by accident. All sends go through one task queue with a global rate limit and fairness between Batches, so the EDT cannot flood the DSP and one huge Batch cannot starve a small one. The answer that comes straight back is only the synchronous DUIS Response, which says whether the DSP accepted or rejected the request. It does not say what the device answered. The DSP's signature on that reply is checked before it is trusted.

| # | App | File:line | What to inspect |
|---|---|---|---|
| 31 | orchestrator | `adapter/ServiceRequestWorkflowImpl.java:92` (`sender(...).send`) | on the `duis-send` queue: rate limit 1/s, fairness key = Batch |
| 32 | orchestrator | `adapter/DuisSenderActivityImpl.java:46` (`send`) / `:49` (`http.send`) | ONE post, no retry. Keep this pause short (30 s limit) |
| 33 | orchestrator | `adapter/DuisSenderActivityImpl.java:65` (`verifier.check`) | the DSP signature on the synchronous response, checked with the pinned DSP certificate |

### Step 8: the wait (asynchronous answer)

**Purpose.** This step is the reason for the whole design. Meters and communications hubs are battery-powered and often not listening, so the real answer can take minutes or hours, or never come. The Service Request workflow simply waits, for a configurable timeout, and while it waits no thread or process is held: Temporal stores a timer, so the wait survives restarts of the Orchestrator. If a DCC Alert N12 or N13 arrives, or the timeout passes, the Service Request is marked failed and is not sent again. "No answer" is itself a result: it is the evidence that a device really is unreachable.

| # | App | File:line | What to inspect |
|---|---|---|---|
| 34 | orchestrator | `adapter/ServiceRequestWorkflowImpl.java:109` (`Workflow.await`) | a durable timer (2 min) in Temporal; nothing runs in the app while it waits |

Look in the Temporal UI (History): `TimerStarted`. Set breakpoints 35 to 38 BEFORE the 5 s elapse (or resume quickly from 33).

### Step 9: DSP → DSP HTTPS Gateway → Response Listener → Temporal Server (signal)

**Purpose.** This is how the late answer finds its way back to the workflow that is waiting for it. The DSP sends Responses and DCC Alerts to the gateway, which passes them over HTTP to the Response Listener, a separate container because it must accept inbound calls while the Orchestrator only makes outbound ones. The Listener first checks the DSP's signature, because nothing in an unverified message, not even its Request ID, can be trusted. It then reads the Request ID, derives the workflow ID from it, and sends a signal to that workflow through the Temporal Server. It never talks to the Orchestrator, and it needs no database of its own, because the workflow ID follows directly from the Request ID. If the Orchestrator is down, the signal waits in the workflow's history until it returns. If Temporal is down, the Listener answers 500 so the gateway delivers the message again.

| # | App | File:line | What to inspect |
|---|---|---|---|
| 35 | response-listener | `adapter/DspMessageResource.java:42` (`routeDspMessage.route`) | `body`: the signed Response, NOT yet trusted |
| 36 | response-listener | `usecase/RouteDspMessage.java:30` (`signatureCheck.verify`) | signature checked FIRST, before reading anything |
| 37 | response-listener | `usecase/RouteDspMessage.java:31` (`reader.read`) then `:33` (`signaller.signal`) | Request ID read, workflow ID derived; no table, no database |
| 38 | response-listener | `adapter/TemporalWorkflowSignaller.java:30` (`...signal(SIGNAL, payload)`) | the signal goes to Temporal, not to the Orchestrator |

Keep the pauses in this app short (the simulator retries every 3 s, 5 times, then drops the message).

### Step 10: Service Request workflow resumes, decodes

**Purpose.** The signal wakes the waiting workflow, which now has to understand what the device said. The Response is a device message in a technical format, so the Orchestrator decodes it (in production with Parsec, existing CSW software; here a placeholder) into Facts: the named values a Flow decides on, such as a register reading or a device status. The workflow returns its outcome and those Facts to its caller, the Flow. The Flow then decides whether to continue with the next Service Request or stop. A DCC Alert ends it as failed, and a Response that cannot be decoded ends it as a decode failure.

| # | App | File:line | What to inspect |
|---|---|---|---|
| 39 | orchestrator | `adapter/ServiceRequestWorkflowImpl.java:137` (`onDspMessage`) | the signal handler adds to a queue |
| 40 | orchestrator | `adapter/ServiceRequestWorkflowImpl.java:114` (`messages.poll`) | Response vs DCC Alert (N12, N13 fail it) |
| 41 | orchestrator | `adapter/ResponseDecoderActivityImpl.java:29` (`decoder.decode`) | Response to Facts (placeholder for Parsec) |

### Step 11: Flow ends, Orchestrator → Kafka

**Purpose.** This is the output of the EDT, and the reason it exists: people and other systems use the results to decide an action, for example sending someone to a property. For each device the Flow produces a Verdict (the device responds, or it does not). The Orchestrator publishes three kinds of event to Kafka: one for each step of each Flow, one for the result of each device, and one for the result of each Batch. It is a producer only. Events are keyed by device, so all events of one device land in one partition and stay in order. Each event carries an ID so that consumers can drop duplicates, because a retried activity can publish the same event twice. The EDT never reads from Kafka.

| # | App | File:line | What to inspect |
|---|---|---|---|
| 42 | orchestrator | `adapter/PlaceholderFlowWorkflowImpl.java:52` and `:55` | Flow step event, then device result; event IDs come from the workflow ID |
| 43 | orchestrator | `adapter/ResultPublisherActivityImpl.java:46` (`publisher.publish`) | topic, key (device ID), event ID, JSON |
| 44 | orchestrator | `adapter/KafkaEventPublisher.java:37` (`producer.send(...).get`) | waits for the broker (acks=all) |
| 45 | orchestrator | `adapter/BatchWorkflowImpl.java:78` (`publishBatchResult`), after `:73` | the Batch result (key = Batch ID) |

## 2. Checks outside the debugger

Temporal UI (http://localhost:8088, namespace `edt`): for each of Batch, Page, Flow, Service Request open the History tab and the Input/Result.
Expected final: Service Request `RESPONDED` with Facts; Flow Verdict `RESPONDS`; Batch result `{"devices":1,"invalidRows":0,"pages":1,"verdicts":{"RESPONDS":1}}` (unverified for exactly one row).

Kafka (3 topics; one message each for the device result and the Batch result, one step):
```bash
MSYS_NO_PATHCONV=1 docker run --rm --network edt apache/kafka:4.1.0 /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic edt.device-results --from-beginning --timeout-ms 8000 --property print.partition=true --property print.key=true --property print.headers=true
```
Repeat with `edt.flow-steps` and `edt.batch-results`.

## 3. Short list if you want the minimum (one breakpoint per container hop)

| Hop in 100.010 | Breakpoint |
|---|---|
| Events → Batch Ingest | `SqsManifestEventConsumer.java:75` |
| Batch Ingest → KMS | `KmsVerifySignatureSpi.java:79` |
| Batch Ingest → Temporal | `TemporalBatchStarter.java:40` |
| Orchestrator → S3 | `S3ManifestBytes.java:23` |
| Orchestrator → DUIS Signer (via Temporal) | `ServiceRequestWorkflowImpl.java:85`, `SignActivityImpl.java:18` |
| DUIS Signer → KMS | `KmsSignatureSpi.java:82` |
| Orchestrator → DSP Gateway | `DuisSenderActivityImpl.java:49` |
| DSP → Response Listener | `DspMessageResource.java:42` |
| Response Listener → Temporal | `TemporalWorkflowSignaller.java:30` |
| Orchestrator → Kafka | `KafkaEventPublisher.java:37` |

## 4. Break it on purpose, one at a time (after the happy path)

1. Bad signature: `... manifest-upload /manifests/one-device.csv --bad-signature`. Expect `Manifest rejected` (logger `edt.security`), no workflow. Stop at breakpoint 9.
2. Duplicate message: replay the same SQS message. Expect `already started`. Stop at breakpoint 11.
3. Device ending `-A1` (edit the CSV): the simulator sends DCC Alert N12. Expect `FAILED_ALERT`, Verdict `NO_RESPONSE`. Stop at breakpoint 40.
4. Pause 40 s at breakpoint 32: the activity times out and the request ends `SEND_FAILED` (one attempt, never resent).
5. Stop the `orchestrator` run during the 5 s wait, start it again: the workflow resumes (state lives in Temporal).

Restarting Floci or `kms-setup` makes new keys: restart the four apps and the simulator afterwards.

## 5. The happy path in plain words

The same journey as sections 1 to 4, as a story with no code. Read this first if the details feel like too much, then go deeper.
The names in **bold** are the boxes in the model.

**The cast, in one line each**
- **Operator:** a person at DCC who starts the whole thing.
- **Manifest Bucket, Manifest Events, KMS, Temporal, Kafka, DSP:** DCC's services. We use them but don't build them.
- **Batch Ingest, Orchestrator, DUIS Signer, Response Listener:** the four apps we build.

**The story**

*Starting the work*
1. The **operator** has a list of devices that seem unreachable. DCC signs the list, and this signed list is the **Manifest**.
2. The operator uploads the Manifest to the **Manifest Bucket** (S3).
3. The bucket saves it and shouts "a new file arrived!". That message goes to **Manifest Events**, a queue.
4. **Batch Ingest** is waiting on that queue and picks up the message. The message only says where the file is, not what is in it.

*Checking it is genuine*
5. Batch Ingest reads the file from the bucket and works out a fingerprint (a hash) of it.
   - **What a hash is:** a short, fixed-size value calculated from the whole content of the file (here SHA-256, always 32 bytes, however big the file is). The same file always gives the same hash. Change a single character and you get a completely different one. It is the file's fingerprint.
   - **Why it needs one:** DCC did not sign the file directly. When it signed the Manifest, it calculated the file's hash and signed that. The signature is stored next to the file, as a tag on the S3 object. To check it, Batch Ingest must first calculate the hash of the file it has just downloaded, with the same method, and see whether it matches what DCC signed.
   - **How it reads the file:** it streams it from the bucket, a chunk at a time, and feeds each chunk into the hash calculation as it arrives. It never holds the whole file in memory, so a Manifest of any size works.
   - **Caveat:** "DCC signs the hash" is how our `manifest-upload` stand-in works, and it matches the model (the verifier checks a digest and a signature with KMS Verify). How the real DCC attaches its signature (the tag name and format) is still to be agreed with DCC (open point 4 in `CLAUDE.md`).
   - **What comes out:** only the 32-byte fingerprint. The file itself is not kept and is not sent anywhere else.
   - **Where to see it:** breakpoints 6 and 7 in section 1, step 3 (`S3ManifestVerifier.java:61` and `KmsVerifySignatureSpi.java:60`).
6. It asks **KMS**: "does this signature match this fingerprint, using DCC's key?" KMS says yes.
   - Batch Ingest sends KMS two small things: the 32-byte fingerprint it just calculated, and the signature it read from the S3 tag. It does not send the file.
   - KMS holds DCC's key and answers yes or no. A "yes" means: this signature was made by DCC, over exactly this fingerprint, so the file is exactly what DCC signed. Batch Ingest never sees the key.
7. If the answer were no, the Manifest would be thrown away and nothing would start.

*Handing over to Temporal*
8. Batch Ingest tells **Temporal**: "start a Batch for this Manifest."
9. Batch Ingest then deletes the queue message and is done. It does not process anything. From here on, Temporal remembers where things are.

*Splitting up the work*
10. The **Orchestrator** is the app that does the work. Temporal hands it the new Batch.
11. The Orchestrator cuts the Manifest into small groups of devices called **Pages**.
12. For each Page, it reads just those rows from the bucket.
13. For each device in the Page, it starts a **Flow**: the diagnosis of that one device. In our version, the Flow is simple: ask the device one question.

*Asking the device*
14. The Flow creates a **Service Request**, which is the question for the device.
15. The Orchestrator is not allowed to sign anything. So it asks **DUIS Signer**: "please sign this."
16. DUIS Signer checks the request is allowed, asks **KMS** to sign it, and writes down what it did.
17. The Orchestrator takes the signed request and sends it, once, to the **DSP**. The DSP is DCC's door to the real devices.
18. The DSP answers straight away with "accepted" (or "rejected"). That is not the device's answer. It only says the request was taken.

*Waiting*
19. Meters run on battery and are often asleep, so the real answer can take minutes or hours.
20. The Orchestrator doesn't sit and watch. It tells Temporal: "wake me when the answer comes, or after two minutes." Then it moves on to other devices.

*The answer comes back*
21. Later, the device replies to the DSP, which passes the reply to the **Response Listener**.
22. The Response Listener checks that the reply really came from the DSP, and then reads which request it belongs to.
23. It does not call the Orchestrator. It tells Temporal: "an answer arrived for request X." Temporal wakes the waiting workflow.
24. The Orchestrator reads the answer and turns it into simple facts, like "meter reads 12345".

*Finishing*
25. The Flow turns that into a verdict for the device: "responds", or "doesn't respond". If there was no answer, or the DSP sent an alert, the verdict is "doesn't respond".
26. The Orchestrator publishes the device's result to **Kafka**. It does the same for each step.
27. When every Page is done, the Batch is finished. The Orchestrator publishes one last message to Kafka: the totals for the whole Batch.
28. Someone at DCC reads the results and decides what to do, for example send an engineer to a house where the device really does not respond.

**The pattern to hold on to**
- Batch Ingest decides whether the work may start.
- The Orchestrator does the work.
- DUIS Signer is the only one that signs.
- The Response Listener only brings answers back.
- **Temporal** sits in the middle: the apps never call each other, they leave messages and tasks in Temporal.
