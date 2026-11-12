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
No breakpoint (it is dcc-tools). Run the upload (from `dcc-tools/`), best with `batch-ingest` stopped or paused so the message stays on the queue:
```bash
MSYS_NO_PATHCONV=1 docker compose run --rm manifest-upload /manifests/one-device.csv
```
Look at: the S3 object version and its `dcc-signature` tag, and the SQS message (bucket, key, version-id).

### Step 2: Batch Ingest ← Manifest Events (take the message)
| # | App | File:line | What to inspect |
|---|---|---|---|
| 1 | batch-ingest | `adapter/SqsManifestEventConsumer.java:46` (`receiveMessage`) | long poll, 20 s wait; `messages` after the call |
| 2 | batch-ingest | `adapter/SqsManifestEventConsumer.java:54` (`handle`) | `message.body()`: the "Object Created" event |
| 3 | batch-ingest | `adapter/SqsManifestEventConsumer.java:75` (`ingestManifest.ingest`) | `manifest` (a `ManifestRef`: bucket, key, versionId) |

### Step 3: Batch Ingest → Manifest Bucket and AWS KMS (verify the signature)
| # | App | File:line | What to inspect |
|---|---|---|---|
| 4 | batch-ingest | `usecase/IngestManifest.java:25` (`verifier.verify`) | rule: verify BEFORE anything else |
| 5 | batch-ingest | `adapter/S3ManifestVerifier.java:44` (`getObjectTagging`) | the base64 signature from the tag |
| 6 | batch-ingest | `adapter/S3ManifestVerifier.java:61` (`s3.getObject`) | the object is streamed, not loaded |
| 7 | batch-ingest | `adapter/kms/KmsVerifySignatureSpi.java:60` (`engineUpdate`) | bytes hashed locally with SHA-256, chunk by chunk |
| 8 | batch-ingest | `adapter/kms/KmsVerifySignatureSpi.java:79` (`kms.verify`) | only the 32-byte DIGEST and the signature go to KMS |
| 9 | batch-ingest | `adapter/S3ManifestVerifier.java:67` (`signature.verify`) | the verdict |

### Step 4: Batch Ingest → Temporal Server (start the Batch workflow)
| # | App | File:line | What to inspect |
|---|---|---|---|
| 10 | batch-ingest | `adapter/TemporalBatchStarter.java:33` (`start`) | workflow ID built from bucket, key, version |
| 11 | batch-ingest | `adapter/TemporalBatchStarter.java:40` (`stub.start`) | `BatchInput`; a second start with the same ID is "already started" |
| 12 | batch-ingest | `adapter/SqsManifestEventConsumer.java:85` (`case Outcome.Started`) then `:96` (`deleteMessage`) | the message is deleted only now |

Look in the Temporal UI: workflow `batch-edt-manifests/one-device.csv/<version>`, status Running.

### Step 5: Orchestrator ← Temporal Server (runs the Batch) and → Manifest Bucket (rows by byte range)
| # | App | File:line | What to inspect |
|---|---|---|---|
| 13 | orchestrator | `adapter/BatchWorkflowImpl.java:45` (`run`) | `input`; the settings (rows-per-page 2, pages-at-a-time 2) |
| 14 | orchestrator | `adapter/BatchWorkflowImpl.java:84` (`startPage`) then `:99` (`Async.function(child::run ...)`) | the Batch starts a child Page workflow |
| 15 | orchestrator | `adapter/PageWorkflowImpl.java:45` (`reader.readPage`) | the Page calls an ACTIVITY to read the rows |
| 16 | orchestrator | `adapter/ManifestReaderActivityImpl.java:29` (`readPage`) | `request.offset()`, `request.rows()` |
| 17 | orchestrator | `adapter/S3ManifestBytes.java:23` (`getObjectAsBytes`) | the `Range: bytes=...` header (line 27) |
| 18 | orchestrator | `adapter/PageWorkflowImpl.java:50` (`pageRead` signal) | the Page tells the Batch where the next Page starts (the inferred signal, not in the model) |
| 19 | orchestrator | `adapter/PageWorkflowImpl.java:62` (`startFlow`) | one Flow per row |
| 20 | orchestrator | `adapter/PlaceholderFlowWorkflowImpl.java:33` (`run`) | `srv` chosen from the device type; the Request ID |

### Step 6: Service Request workflow: build, sign
| # | App | File:line | What to inspect |
|---|---|---|---|
| 21 | orchestrator | `adapter/ServiceRequestWorkflowImpl.java:80` (`run`) | workflow ID = `service-request-<RequestId>` (UI) |
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
| # | App | File:line | What to inspect |
|---|---|---|---|
| 31 | orchestrator | `adapter/ServiceRequestWorkflowImpl.java:92` (`sender(...).send`) | on the `duis-send` queue: rate limit 1/s, fairness key = Batch |
| 32 | orchestrator | `adapter/DuisSenderActivityImpl.java:46` (`send`) / `:49` (`http.send`) | ONE post, no retry. Keep this pause short (30 s limit) |
| 33 | orchestrator | `adapter/DuisSenderActivityImpl.java:65` (`verifier.check`) | the DSP signature on the synchronous response, checked with the pinned DSP certificate |

### Step 8: the wait (asynchronous answer)
| # | App | File:line | What to inspect |
|---|---|---|---|
| 34 | orchestrator | `adapter/ServiceRequestWorkflowImpl.java:109` (`Workflow.await`) | a durable timer (2 min) in Temporal; nothing runs in the app while it waits |

Look in the Temporal UI (History): `TimerStarted`. Set breakpoints 35 to 38 BEFORE the 5 s elapse (or resume quickly from 33).

### Step 9: DSP → DSP HTTPS Gateway → Response Listener → Temporal Server (signal)
| # | App | File:line | What to inspect |
|---|---|---|---|
| 35 | response-listener | `adapter/DspMessageResource.java:42` (`routeDspMessage.route`) | `body`: the signed Response, NOT yet trusted |
| 36 | response-listener | `usecase/RouteDspMessage.java:30` (`signatureCheck.verify`) | signature checked FIRST, before reading anything |
| 37 | response-listener | `usecase/RouteDspMessage.java:31` (`reader.read`) then `:33` (`signaller.signal`) | Request ID read, workflow ID derived; no table, no database |
| 38 | response-listener | `adapter/TemporalWorkflowSignaller.java:30` (`...signal(SIGNAL, payload)`) | the signal goes to Temporal, not to the Orchestrator |

Keep the pauses in this app short (the simulator retries every 3 s, 5 times, then drops the message).

### Step 10: Service Request workflow resumes, decodes
| # | App | File:line | What to inspect |
|---|---|---|---|
| 39 | orchestrator | `adapter/ServiceRequestWorkflowImpl.java:137` (`onDspMessage`) | the signal handler adds to a queue |
| 40 | orchestrator | `adapter/ServiceRequestWorkflowImpl.java:114` (`messages.poll`) | Response vs DCC Alert (N12, N13 fail it) |
| 41 | orchestrator | `adapter/ResponseDecoderActivityImpl.java:29` (`decoder.decode`) | Response to Facts (placeholder for Parsec) |

### Step 11: Flow ends, Orchestrator → Kafka
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
