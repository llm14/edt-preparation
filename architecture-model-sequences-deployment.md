# EDT architecture model: sequences and deployment (supplement)

> Extracted read-only from `edt_likec4.html` (compiled LikeC4 build, JS object literals plus embedded PlantUML).
> Complements `architecture-model.md`, which lacks these views. NOT the editable `.c4` source.
> Source paths named in the build: `src/sequences/interaction-start-batch.c4`, `interaction-device-flow.c4`,
> `interaction-service-request.c4`, `src/views/deployment-model.c4`, `src/views/index.c4`.
> Sections marked **[INFERRED]** are my reading, not text from the model.

---

## 1. Sequence views ("Sequences / 010 - Interactions")

### 010.010 - A Batch (id `interaction-start-batch`)

Model description: the operator uploads a signed Manifest to the S3 Manifest Bucket. EventBridge puts an object created
message on the Manifest Events queue; a Batch Ingest replica takes it, streams the Manifest to compute its digest, checks
the signature with AWS KMS and starts the Batch workflow, whose ID comes from the object version, so a message delivered
twice starts it once; then it deletes the message. A Manifest without a valid signature is rejected and logged, and no
workflow starts. The Batch workflow runs its Pages; each Page reads its rows from S3 and runs their device Flows
(sequence 010.020), up to the configured number at a time, and returns its Verdict counts. The Batch continues as new every
so many Pages, and after the last one publishes the Batch result to Kafka.

Steps (from the PlantUML):

1. DS&A Operator -> Manifest Bucket: upload the signed Manifest (HTTPS, S3)
2. Manifest Bucket -> Manifest Events: object created (EventBridge)
3. Manifest Event Consumer -> Manifest Events: receive message (long poll)
4. Manifest Event Consumer -> Manifest Signature Verifier: check the Manifest
5. Manifest Signature Verifier -> Manifest Bucket: get the object and its signature (streamed)
6. Manifest Signature Verifier -> AWS KMS: Verify the digest and signature
7. AWS KMS -> Manifest Signature Verifier: valid
8. Manifest Event Consumer -> Batch Starter: start the Batch
9. Batch Starter -> Batch Workflow: start Batch workflow (ID from the object version)
10. Manifest Event Consumer -> Manifest Events: delete message
11. Loop, per Page:
    - Batch Workflow -> Page Workflow: start the next Page
    - Page Workflow -> Manifest Reader Activity: read the rows of the Page
    - Manifest Reader Activity -> Manifest Bucket: byte-range read; rows returned to the Page
    - Page Workflow -> Flow Workflow: run a device Flow per row, up to the configured number at a time (010.020)
    - Flow Workflow -> Page Workflow: Verdict of each device
    - Page Workflow -> Batch Workflow: Verdict counts of the Page
12. Batch Workflow -> Result Publisher Activity -> Kafka: Batch result

Participants: Operator, Manifest Event Consumer, Manifest Signature Verifier, AWS KMS, Batch Starter, Batch Workflow,
Page Workflow, Manifest Reader Activity, Flow Workflow, Result Publisher Activity, Kafka (plus Manifest Bucket and
Manifest Events).

Notes **[INFERRED]**:
- Step 10 (delete message) comes after the Batch starts, matching the container text: "A message is deleted only after the
  Batch has started". The "rejected Manifest" branch is described in prose only; the diagram shows the happy path.
- "Continue as new every so many Pages" is in the prose, not drawn as a step.

### 010.020 - A Device Flow (id `interaction-device-flow`)

Model description: a Page starts one Flow workflow per device (sequence 010.010). The Flow takes its approved Flow from the
Flow Catalogue: the Service Requests to send, their order, and the decision after each Response. It runs one Service Request
workflow at a time (sequence 010.030), so the Service Requests of a device are never sent at the same time, publishes each
step to Kafka, and after each outcome decides whether to continue with the next Service Request or stop. A failed Service
Request (DCC Alert N12 or N13, or the timeout) is not sent again. At the end it publishes the device Flow result with its
Verdict and returns the Verdict to its Page.

Steps:

1. Page Workflow -> Flow Workflow: start Flow workflow (device, Flow) (010.010)
2. Flow Workflow -> Flow Catalogue Activity: get the approved Flow
3. Flow Catalogue Activity -> Flow Workflow: Service Requests, order and decisions
4. Loop, per Service Request:
    - Flow Workflow -> Service Request Workflow: run the next Service Request (010.030)
    - Service Request Workflow -> Flow Workflow: outcome and Facts
    - Flow Workflow -> Result Publisher Activity -> Kafka: publish the step (step event)
    - Flow Workflow -> Flow Workflow: decide: next Service Request or stop
5. Flow Workflow -> Result Publisher Activity -> Kafka: device Flow result (Verdict)
6. Flow Workflow -> Page Workflow: Verdict

Notes **[INFERRED]**: the loop is sequential by design, so one device never has two Service Requests in flight. The step
event is published by the Flow workflow, not by the Service Request workflow.

### 010.030 - One Service Request (id `interaction-service-request`)

Model description: the Service Request workflow builds it, the DUIS Signer checks it and signs it through AWS KMS with the
key of its User Role, the DUIS Sender posts it through the DSP HTTPS Gateway, which sends it to the DSP over HTTPS and
retries transient errors, and the Response comes back through the gateway to the Response Listener, which signals the
waiting workflow. A DCC Alert N12 or N13, or the timeout, ends the wait instead: the Service Request is marked failed and
never sent again. The outcome and the Facts go back to the device Flow (sequence 010.020), which publishes the step and
decides whether to continue.

Steps:

1. Flow Workflow -> Service Request Workflow: start Service Request workflow (ID from the Request ID) (010.020)
2. Service Request Workflow -> DUIS Request Builder Activity: build the Service Request; unsigned request returned
3. Service Request Workflow -> Sign Activity (DUIS Signer): sign the Service Request
4. Sign Activity -> AWS KMS: policy checked; Sign with the User Role key; signature returned
5. Sign Activity -> Service Request Workflow: signed Service Request
6. Service Request Workflow -> DUIS Sender Activity: send the Service Request (rate-limited)
7. DUIS Sender -> DSP HTTPS Gateway: POST signed Service Request
8. DSP HTTPS Gateway -> DSP: Service Request over HTTPS, DCCKI TLS (retries transient errors)
9. DSP -> DSP HTTPS Gateway: synchronous DUIS Response (HTTPS)
10. DSP HTTPS Gateway -> DUIS Sender: synchronous DUIS Response
11. DUIS Sender -> Service Request Workflow: synchronous DUIS Response, signature checked
12. DSP -> Smart Metering Devices: command over the SM WAN; device response returned
13. DSP -> DSP HTTPS Gateway: Response (or DCC Alert N12 / N13) over HTTPS
14. DSP HTTPS Gateway -> Response Endpoint: POST Response
15. Response Endpoint -> Workflow Signaller: Response, signature checked
16. Workflow Signaller -> Service Request Workflow: signal with the Response
17. Service Request Workflow -> Response Decoder Activity: decode the Response; Facts returned
18. Service Request Workflow -> Flow Workflow: outcome and Facts

Notes **[INFERRED]**:
- Steps 12 and 13 happen asynchronously after step 11, possibly hours later (see CLAUDE.md "Why it is asynchronous").
- The failure branch (N12, N13, timeout) is in the prose only, not drawn as a separate path.
- The sequence shows the Orchestrator never touches a key: signing is a separate Temporal activity in another
  container, with an activity result carrying the signed bytes.

---

## 2. Deployment views ("300 - Deployment Model")

### 300.010 - Kubernetes Cluster (id `deployment-model`)

Model description: where the EDT runs, the same in PIT, SIT and production: four Deployments in the `edt` namespace of the
DCC Kubernetes cluster, each with at least two pods and its own autoscaler, next to Temporal, the DCC platform services, the
DaemonSets on every worker node and the AWS managed services. Blue is what the example Kubernetes manifests of CSW define;
grey is provided by DCC EIT or AWS. Every EDT pod is stateless, so the EDT needs no PersistentVolume; its logs go to standard
output and the log agent DaemonSet reads them from the node.

Nodes named in the view:

- Boundaries: DCC AWS Account, Kubernetes Cluster, EC2 worker nodes.
- Namespace `edt`: Deployments `batch-ingest`, `orchestrator`, `duis-signer`, `response-listener`; their ConfigMaps
  (`batch-ingest`, `orchestrator`, `duis-signer`, `response-listener`); KEDA, HorizontalPodAutoscaler (CPU),
  PodDisruptionBudget, NetworkPolicy `edt`.
- Namespace `temporal`: Deployments `frontend`, `history`, `matching`, `worker`, `web` (Temporal server services and UI).
- DCC platform services: KEDA, Metrics Server, Node Autoscaler, DaemonSet `log-agent`, DaemonSet `mesh proxy`, the DCC
  secrets operator (copies from Secrets Manager).
- AWS managed services: AWS KMS, AWS Secrets Manager, Amazon ECR, Amazon S3, Amazon EventBridge and SQS, Amazon RDS for
  PostgreSQL (Temporal persistence), Amazon OpenSearch Service (Temporal visibility), Amazon CloudWatch Logs.
- Outside: DSP HTTPS Gateway, DSP, Kafka, Monitoring, DCC Identity Provider, DS&A Operator.

### 300.020 - Namespace edt (id `deployment-model-edt-namespace`)

Model description: the Kubernetes objects of the EDT, as the example manifests of CSW define them. Each Deployment runs at
least two stateless pods, scaled by its own autoscaler (KEDA on the SQS queue length or on the Temporal task queue backlog,
a Horizontal Pod Autoscaler on CPU for the Response Listener), kept to one pod at least during node maintenance by a
PodDisruptionBudget, configured by its ConfigMap, and given AWS permissions only through the IAM role of its
ServiceAccount, so no AWS credential is stored in the cluster. There are two Secrets, both copied from AWS Secrets Manager
by the DCC secrets operator and mounted read-only: the certificate store, into the pods that need certificates, and the
Kafka client certificate and key, into the Orchestrator pods only (mutual TLS to Kafka, to be confirmed with DCC). The DSP
HTTPS Gateway reaches the Response Listener pods through their Service. A NetworkPolicy denies every other connection.

Scaling summary:

| Deployment | Autoscaler | Signal | Source |
|---|---|---|---|
| `batch-ingest` | KEDA | Manifest Events queue length (SQS) | KEDA scaler summary in the build |
| `orchestrator` | KEDA | Temporal task queue backlog | same |
| `duis-signer` | KEDA | signing task queue backlog | same |
| `response-listener` | HorizontalPodAutoscaler | CPU | model text above |

Secrets:

| Secret | Mounted into | Purpose |
|---|---|---|
| certificate store (PKCS#12, password-protected) | pods that need certificates (Orchestrator, DUIS Signer, Response Listener) | User Role certificates, DSP trust certificate |
| `edt-kafka-client` (named in the interface `edt.orchestratorReqKafka`) | Orchestrator only | Kafka mTLS client certificate and key (to be confirmed) |

---

## 3. Other views present in the build but not extracted

- `000 - Landscape` (title only seen; content not extracted).
- `src/views/index.c4` (view index; not extracted).

## 4. What the HTML does not contain

No `.c4` DSL text, no CloudHSM, PKCS#11 or vendor-library mention, no word "idempotent", no Gradle or build information,
no flow content (the 3 approved Flows, their Service Requests, the allowlists), no Kafka topic or schema definitions.

---

## 5. Build implications of using Gradle (not Maven) with Java 25 **[INFERRED, not in the model]**

Nothing in the model or `CLAUDE.md` names a build tool. The `.idea` config only says JDK 25. These are my notes; verify
each against current docs before relying on it, since I did not check versions.

- **Quarkus supports Gradle** through its Gradle plugin (`io.quarkus`) and BOM platform dependency. Plain Quarkus, as in
  CLAUDE.md, fits this. Check that the Quarkus version you pick supports Java 25 and the Gradle version you use.
- **Module layout.** CLAUDE.md prefers one module per application with structured packages and an architecture test.
  With Gradle that is four subprojects in one multi-project build: `batch-ingest`, `orchestrator`, `duis-signer`,
  `response-listener`, with `settings.gradle(.kts)` including them.
- **Shared code.** The model has reused CSW software (DUIS Library, Parsec Response Decoder, Response Listener) and shared
  concepts (Request ID, DUIS types, workflow and task-queue names, activity interfaces). In Gradle this suggests either
  a small shared subproject or the reused libraries as external dependencies. Whether the reused libraries are published
  somewhere (an internal Maven repository) is a question for your colleague. **[CONFIRM]**
- **Activity interfaces across containers.** The Orchestrator's Service Request workflow calls the DUIS Signer's Sign
  Activity over Temporal, from a different container. The Orchestrator needs the activity interface and its data types
  but must not depend on the signer's implementation (it never holds a signing key). In Gradle that is a small
  contract subproject (interfaces and DTOs only) that both depend on. The same applies to the Response Listener signalling the
  Service Request workflow, which needs the signal method's interface and the workflow ID derivation.
- **Dependency management.** Use a Gradle version catalog (`gradle/libs.versions.toml`) and the Quarkus BOM, so the Temporal
  SDK, AWS SDK, Kafka client and test libraries are pinned once.
- **Architecture test.** ArchUnit with JUnit 5 runs from `./gradlew test` and can enforce the Clean Architecture package
  rules per module. Needs no plugin beyond the Java one.
- **Test crypto provider.** The software versus HSM-backed provider selection by configuration maps to Quarkus
  configuration profiles; nothing build-specific beyond keeping the software provider on the test classpath only.
- **Container images.** The deployment view pulls from Amazon ECR. Quarkus can build images through its container-image
  extensions from Gradle; which one DCC expects is not stated. **[CONFIRM]**
- **Kubernetes scripts.** CLAUDE.md says we write minimal manifests for testing. The model says CSW delivers "example
  manifests" (KEDA scalers, HPA, PDB, NetworkPolicy, ConfigMaps, Secrets). These can live in a top-level `deploy/` folder,
  outside Gradle.
- **Checkstyle.** `.idea/checkstyle-idea.xml` has Checkstyle 14.4.0 with the Sun and Google bundled rule sets but no active
  location. The Gradle `checkstyle` plugin would need a config file in the repo for CI to match the IDE. Which ruleset
  the team uses is unknown. **[CONFIRM]**
- **Toolchain.** Declare a Java 25 toolchain in Gradle so builds do not depend on the machine's default JDK.
