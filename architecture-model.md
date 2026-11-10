# EDT architecture model (snapshot)

> Read-only snapshot extracted from a published LikeC4 static build (`index.html`). It is NOT the editable `.c4` source and may be out of date. If the `.c4` source files become available, they replace this file. Where this file disagrees with the code, report it as a mismatch.

Contents: 1) views, 2) elements, 3) relationships, 4) what each view shows.

## 1. Views

### 200 - Components / 200.010 - Batch Ingest (id: `batch-ingest-component-model`, source: `src/views/batch-ingest-component-model.c4`)

The Batch Ingest (developed by CSW) with its components and interfaces, and the existing DCC services it uses: the Manifest Events queue that tells it of each upload, the S3 Manifest Bucket, AWS KMS to check the Manifest signature, and the Temporal Server to start the Batch workflow. The DCC log agent reads its local logs and DCC monitoring its health and metrics.

### 100 - Component Model / 100.010 - Containers (id: `component-model-containers`, source: `src/views/component-model-containers.c4`)

The containers of the Enhanced Diagnostics Tool (blue: developed by CSW; green: the DCC Adapter Response Listener, existing CSW software reused and adapted) and the existing DCC services they use (grey: provided and operated by DCC EIT). Lines: dark blue Temporal gRPC, magenta DUIS over HTTP and HTTPS, brown Kafka, grey other. Every EDT container writes local logs in ECS JSON, which the DCC log agent sends to CloudWatch Logs, and serves health and Prometheus metrics endpoints, which DCC monitoring scrapes. The certificate store, mounted read-only into the Orchestrator, the DUIS Signer and the Response Listener, is shown in their component views.

### 200 - Components / 200.030 - DUIS Signer (id: `duis-signer-component-model`, source: `src/views/duis-signer-component-model.c4`)

The DUIS Signer (developed by CSW) with its components and interfaces, and the existing DCC services it uses: the Temporal Server for its signing tasks, AWS KMS that holds the keys of the two User Roles, and the certificate store. The DCC log agent reads its local audit logs and DCC monitoring its health and metrics.

### 200 - Components / 200.020 - Orchestrator (id: `orchestrator-component-model`, source: `src/views/orchestrator-component-model.c4`)

The Orchestrator (developed by CSW; the DUIS Library and the Parsec Response Decoder are existing CSW software) with its components and interfaces, and the existing DCC services it uses: the Temporal Server, the S3 Manifest Bucket, the DSP HTTPS Gateway, Kafka for the results and the certificate store. The DCC log agent reads its local logs and DCC monitoring its health and metrics.

### 200 - Components / 200.040 - Response Listener (id: `response-listener-component-model`, source: `src/views/response-listener-component-model.c4`)

The Response Listener (the DCC Adapter Response Listener, existing CSW software reused and adapted; the Workflow Signaller is new) with its components and interfaces: the DSP HTTPS Gateway posts each Response and DCC Alert to it, and it signals the Service Request workflow on the Temporal Server. The DCC log agent reads its local logs and DCC monitoring its health and metrics.

## 2. Elements

Kinds: actor, externalSystem, system, container, component, store, queue, interface (provided/required endpoints).

### DS&A Operator (`operator`)

- Kind: actor · Colour: actorFill
- Summary: Runs diagnostics on many devices at once
- Description: A member of the DCC (Data Communications Company) Diagnostics and Analytics (DS&A) team. Uploads a signed Manifest (bulk file) of devices to the Manifest Bucket to start a Batch, follows the Batch and each device Flow in the Temporal UI, and cancels a Batch there. Restarts a Batch by uploading the file again, or a file with the remaining devices.

### DSP (`dsp`)

- Kind: externalSystem · Colour: actorFill
- Summary: DCC Data Service Provider
- Description: The DSP (DCC Data Service Provider) accepts DUIS (DCC User Interface Specification) Service Requests over HTTPS, returns the synchronous DUIS Response, sends the command to the device over the SM WAN (Smart Metering Wide Area Network), retries it as the DUIS error handling strategy defines, and posts the signed Response, or a DCC Alert such as N12 or N13, back over HTTPS.

### Smart Metering Devices (`devices`)

- Kind: actor · Colour: actorFill
- Summary: Communications Hubs and meters
- Description: The CHs (Communications Hubs) and the meters behind them named in a Manifest. They receive only non-critical Service Requests, sent as an Unknown Remote Party.

### DCC Identity Provider (`idp`)

- Kind: externalSystem · Colour: actorFill
- Summary: Signs operators in
- Description: The DCC Identity Provider (IdP), which the Temporal UI uses to sign operators in with OIDC (OpenID Connect).

### DCC Infrastructure (EIT) (`dcc`)

- Kind: system · Colour: nodeFill
- Summary: Existing services provided and operated by DCC
- Description: The platform the EDT (Enhanced Diagnostics Tool) runs on, provided and operated by DCC Enterprise IT (EIT): the Kubernetes cluster, Temporal, the HTTPS connection to and from the DSP (DCC Data Service Provider), Kafka, AWS KMS (Key Management Service), the S3 Manifest Bucket and its events, log collection into CloudWatch and monitoring. DCC infrastructure also provides time synchronisation, TLS and mutual TLS between services, and encryption at rest.

#### DSP HTTPS Gateway (`dcc.dspGateway`)

- Kind: container · Colour: dccExisting
- Summary: HTTPS connection to and from the DSP
- Description: DCC reverse proxy and load balancer between the EDT and the DSP (DCC Data Service Provider). Outbound, it takes each signed Service Request over plain HTTP, sends it to the DSP over HTTPS with DCCKI (DCC Key Infrastructure) TLS, retries when the DSP cannot be reached or returns a transient error, and returns the synchronous DUIS (DCC User Interface Specification) Response. Inbound, it terminates the HTTPS connection from the DSP (TLS offload, load balancing) and passes each Response and DCC Alert on over plain HTTP to the Response Listener.

#### Temporal Server (`dcc.temporalServer`)

- Kind: container · Colour: dccExisting
- Summary: Durable execution of the EDT workflows
- Description: Temporal Server (open source, self-hosted) installed and run by DCC EIT, with its PostgreSQL persistence and its OpenSearch visibility store. It keeps the history of every workflow, its timers and signals, and the task queues from which the EDT workers take their work, including the server-side rate limits and fairness of those queues. Its frontend, history and matching services scale out separately; the number of history shards is fixed at install, so DCC sets it for the target load with the CSW sizing guidance. The EDT namespace has the search attributes that the Temporal UI filters on (device, Flow, Batch, Verdict) and a retention period after which closed workflows are deleted.

#### Temporal UI (`dcc.temporalUi`)

- Kind: container · Colour: dccExisting
- Summary: Web interface to follow and cancel Batches
- Description: Temporal Web UI, run by DCC EIT and signed in through the DCC Identity Provider. Operators see the status and results of each Batch, drill down to each device Flow and its outcome, including failures, and cancel a Batch. CSW builds no user interface of its own.

#### Kafka (`dcc.kafka`)

- Kind: container · Colour: dccExisting
- Summary: Event streaming platform that receives the results
- Description: DCC Kafka cluster, the reporting seam of the EDT. The Orchestrator publishes an event for each step of each device Flow, the result of each device Flow and the result of each Batch, keyed by device, for DCC reporting to consume. CSW is a producer only. Assumed, to be confirmed with DCC: Kafka authenticates the EDT by mutual TLS with a client certificate that DCC issues.

#### AWS KMS (`dcc.kms`)

- Kind: container · Colour: dccExisting
- Summary: Holds the signing keys; keys never leave it
- Description: AWS KMS (Key Management Service), FIPS 140-3 level 3. Holds the SMKI (Smart Metering Key Infrastructure) private keys of the two DUIS User Roles the EDT sends as, Other User and Registered Supplier Agent, and the key with which DCC signs Manifests. The keys never leave KMS: the DUIS Signer signs through it and the Batch Ingest checks Manifest signatures through it.

#### Manifest Bucket (`dcc.manifestBucket`)

- Kind: store · Technology: Amazon S3 · Colour: dccExisting
- Summary: Where operators upload signed Manifests
- Description: Amazon S3 bucket provided by DCC, with versioning and encryption at rest. Operators start a Batch by uploading a Manifest (bulk file) to it, signed by DCC: the signature is carried with the object, for example as an object tag, in a format to be agreed with DCC. Each upload is a new object version, so resubmitting a file starts a new Batch. The Batch Ingest reads the Manifest to check its signature and the Orchestrator reads its rows page by page.

#### Manifest Events (`dcc.manifestEvents`)

- Kind: queue · Technology: Amazon EventBridge · Amazon SQS · Colour: dccExisting
- Summary: One message per uploaded Manifest
- Description: An Amazon EventBridge rule, provided by DCC, that puts one message on an Amazon SQS queue for each object created in the Manifest Bucket, with a dead-letter queue for messages that keep failing. The queue decouples the upload from the EDT: a Manifest uploaded while the EDT is down is processed when it comes back, and any number of Batch Ingest replicas can take messages from it.

#### Certificate Store (`dcc.certificateStore`)

- Kind: store · Colour: dccExisting
- Summary: Certificates, mounted read-only
- Description: Password-protected PKCS#12 store, kept as a secret by DCC and mounted read-only into the EDT pods. It holds certificates only, because KMS cannot hold them: the certificates of the two User Roles, used by the DUIS Signer, and the trusted DSP signing certificate, used to check the signature of every DUIS Response and DCC Alert.

#### Log Agent (`dcc.logAgent`)

- Kind: container · Colour: dccExisting
- Summary: Collects the local logs into CloudWatch
- Description: DCC log agent on every Kubernetes node. It reads the ECS (Elastic Common Schema) JSON logs that each EDT container writes locally and sends them to CloudWatch Logs, where DCC stores and exports them.

#### Monitoring (`dcc.monitoring`)

- Kind: container · Colour: dccExisting
- Summary: DCC monitoring tools
- Description: DCC monitoring, which scrapes the health endpoints and the Prometheus metrics of each EDT container (Temporal SDK and JVM metrics).

#### CloudWatch Logs (`dcc.cloudWatch`)

- Kind: store · Colour: dccExisting
- Summary: Central log store
- Description: Amazon CloudWatch Logs, filled by the DCC log agent. DCC keeps and exports the EDT logs from there.

### Enhanced Diagnostics Tool (`edt`)

- Kind: system · Colour: nodeFill
- Summary: Runs approved diagnostic Flows on many devices
- Description: Lets the DCC (Data Communications Company) act as a user of the smart metering network. For each device in a signed Manifest (bulk file), it runs one of the 3 approved Flows of non-critical DUIS (DCC User Interface Specification) Service Requests, sent one after the other, and publishes the outcome of each device and of each Batch. Built by CSW on Temporal, which DCC provides.

#### Batch Ingest (`edt.batchIngest`)

- Kind: container · Technology: Java 25 · Quarkus · Temporal Java SDK · Colour: cswNew
- Summary: Starts one Batch per signed Manifest
- Description: Quarkus application that takes the object created messages of the Manifest Bucket from the Manifest Events queue. For each, it checks the signature of the Manifest through AWS KMS (Key Management Service) before anything else, rejects a Manifest without a valid signature, and starts one Batch workflow per valid Manifest, well within the 60 seconds of the submission. The workflow ID is derived from the bucket, key and object version, so a message delivered twice starts the Batch once. A message is deleted only after the Batch has started; one that keeps failing goes to the dead-letter queue. Stateless: any number of replicas.

#### Orchestrator (`edt.orchestrator`)

- Kind: container · Technology: Java 25 · Quarkus · Temporal Java SDK · Colour: cswNew
- Summary: Runs the Batch, Flow and Service Request workflows
- Description: Quarkus application and Temporal worker that runs the workflows: one Batch per Manifest, split into Pages of rows so that a Manifest of any size stays within the Temporal history limits; one Flow per device, which sends the Service Requests of its approved Flow one after the other and decides after each Response whether to continue or stop; and one Service Request workflow per Service Request, identified by its DUIS Request ID, which waits durably for the Response. It sends each signed Service Request through the DSP HTTPS Gateway at no more than a configurable number per second across all replicas, shared fairly between the running Batches, decodes the Responses and publishes each step and result to Kafka. It holds no state of its own and never holds a signing key: it scales out horizontally, with replicas added on the backlog of its task queues.

#### DUIS Signer (`edt.duisSigner`)

- Kind: container · Technology: Java 25 · Quarkus · Temporal Java SDK · Colour: cswNew
- Summary: The only container that can sign Service Requests
- Description: Quarkus application and Temporal worker on its own rate-limited task queue. It checks each Service Request against the DUIS XML schema and the SRV allowlist of each User Role, chooses the User Role (Other User where both may send the SRV), signs it through AWS KMS with the key of that User Role, and audits every decision. No other container can sign. Stateless; its task queue rate limit keeps the KMS Sign calls within the KMS request quota.

#### Response Listener (`edt.responseListener`)

- Kind: container · Technology: Java 25 · Quarkus · Temporal Java SDK · Colour: cswReused
- Summary: Turns each Response into a workflow signal
- Description: The Response Listener of the CSW DCC Adapter, reused and adapted. It receives each DUIS Response and DCC Alert that the DSP HTTPS Gateway passes on, checks its DSP signature, reads its Request ID (for a DCC Alert N12, N13 or N53, the Request ID it names) and signals the Service Request workflow with that ID. A message whose signature fails is discarded and logged as a security event; a Response whose workflow has ended is logged as an orphan. Stateless, behind the DCC load balancer: scales out horizontally on CPU.

##### Manifest Event Consumer (`edt.batchIngest.eventConsumer`)

- Kind: component · Colour: cswNew
- Summary: Takes the object created messages
- Description: Long-polls the Manifest Events queue, hands each message on and deletes it once the Batch has started or the Manifest has been rejected. Logs the receipt and outcome of each Manifest.

##### Manifest Signature Verifier (`edt.batchIngest.manifestVerifier`)

- Kind: component · Colour: cswNew
- Summary: Checks the DCC signature of a Manifest
- Description: Streams the Manifest from S3 to compute its SHA-256 digest and checks the signature carried with the object with the AWS KMS Verify operation on the DCC Manifest signing key, so even a large Manifest is never held in memory.

##### Batch Starter (`edt.batchIngest.batchStarter`)

- Kind: component · Colour: cswNew
- Summary: Starts the Batch workflow
- Description: Starts the Batch workflow of a valid Manifest on the Temporal Server, with an ID derived from the object version.

##### Batch Workflow (`edt.orchestrator.batchWorkflow`)

- Kind: component · Colour: cswNew
- Summary: One per Manifest; runs its Pages
- Description: Splits the Manifest into Pages of a configurable number of rows and keeps a configurable number of Pages running, so the devices processed at a time are Pages times Flows per Page. Continues as new after a number of Pages, carrying the offset and the Verdict counts, so its history stays small for any Manifest. Shows the progress and the Verdict counts in the Temporal UI; cancelling it cancels its Pages and their Flows. Publishes the Batch result.

##### Page Workflow (`edt.orchestrator.pageWorkflow`)

- Kind: component · Colour: cswNew
- Summary: One per Page of rows; runs their Flows
- Description: Reads its rows of the Manifest and keeps at most the configured number of device Flows running, starting the next as one ends. Returns the Verdict counts of its rows.

##### Flow Workflow (`edt.orchestrator.flowWorkflow`)

- Kind: component · Colour: cswNew
- Summary: One per device; runs one approved Flow
- Description: Runs one of the 3 approved Flows against one device: sends its Service Requests one after the other, in the order DCC defined, and after each Response decides whether to continue with the next one or stop, with the Verdict. Publishes the device Flow result.

##### Service Request Workflow (`edt.orchestrator.serviceRequestWorkflow`)

- Kind: component · Colour: cswNew
- Summary: One per Service Request; waits for the Response
- Description: Builds, signs (through the DUIS Signer) and sends one Service Request, then waits durably for its Response or for a DCC Alert N12 or N13, up to a configurable timeout longer than the DSP retry period. N12, N13 or the timeout mark the Service Request failed; it is never sent again. Its workflow ID is derived from the DUIS Request ID, so the Response Listener can signal it without any table of its own.

##### Manifest Reader Activity (`edt.orchestrator.manifestReader`)

- Kind: component · Colour: cswNew
- Summary: Reads the rows of a Page from S3
- Description: Temporal activity that reads the rows of one Page with an S3 byte-range read, so no Manifest is ever loaded whole; invalid rows are counted and logged.

##### Flow Catalogue Activity (`edt.orchestrator.flowCatalogue`)

- Kind: component · Colour: cswNew
- Summary: The 3 approved Flows
- Description: Temporal activity that returns an approved Flow from the catalogue: for each Flow, the Service Requests to send, their order, and the decision after each Response, as DCC provides them before the start of the project. A Flow not in the catalogue is rejected.

##### DUIS Request Builder Activity (`edt.orchestrator.requestBuilder`)

- Kind: component · Colour: cswNew
- Summary: Builds the unsigned Service Request
- Description: Temporal activity that builds the DUIS (DCC User Interface Specification) XML of a Service Request for the device or its Communications Hub, with its Request ID.

##### DUIS Sender Activity (global rate limit) (`edt.orchestrator.duisSender`)

- Kind: component · Colour: cswNew
- Summary: Sends a signed Service Request, rate-limited
- Description: Temporal activity on its own task queue, shared by every Service Request workflow of every Batch, whose rate limit the Temporal Server enforces globally, across all workflows and all replicas: the configured maximum of Service Requests per second sent to the DSP. It is the only place a Service Request leaves the EDT, so no workflow or replica can exceed it. Fairness keyed by Batch shares that rate between Batches, so a large Batch does not hold up a small one. Posts the signed Service Request to the DSP HTTPS Gateway once, with no retry of its own (DCC infrastructure retries), and returns the synchronous DUIS Response after checking its DSP signature.

##### DUIS Library (`edt.orchestrator.duisLibrary`)

- Kind: component · Colour: cswReused
- Summary: DUIS XML, schema and signature checks
- Description: Reused from the DCC Adapter: reads DUIS (DCC User Interface Specification) messages and checks the DSP XML signature with the trusted DSP certificate.

##### Response Decoder Activity (`edt.orchestrator.responseDecoder`)

- Kind: component · Colour: cswReused
- Summary: Decodes Responses and extracts the Facts
- Description: Temporal activity that decodes the GBCS (Great Britain Companion Specification) payload of each Response with Parsec, existing CSW software, and takes the Facts the Flow decides on.

##### Result Publisher Activity (`edt.orchestrator.resultPublisher`)

- Kind: component · Colour: cswNew
- Summary: Publishes steps and results to Kafka
- Description: Temporal activity that publishes to Kafka an event for each step of a device Flow (Service Request, outcome, Facts), the result of each device Flow (Verdict) and the result of each Batch, keyed by device. Each event carries an ID derived from its workflow and step, so a consumer can drop the duplicate that a retried activity may produce.

##### Sign Activity (`edt.duisSigner.signActivity`)

- Kind: component · Colour: cswNew
- Summary: Checks and signs one Service Request
- Description: Temporal activity on the rate-limited signing task queue, the only entry point of the DUIS Signer: runs the Signing Policy Check, signs through the KMS Signer with the key of the chosen User Role, records the decision with the Audit Log, and returns the signed Service Request or the reason it was refused.

##### Signing Policy Check (`edt.duisSigner.signingPolicy`)

- Kind: component · Colour: cswNew
- Summary: Checks a Service Request before signing
- Description: Stops at the first failure: valid against the DUIS XML schema, SRV on the allowlist of a User Role, originator is the User ID of that role, device type allowed for the SRV. DCC provides the SRV allowlists before the start of the project.

##### KMS Signer (`edt.duisSigner.kmsSigner`)

- Kind: component · Colour: cswNew
- Summary: Signs through AWS KMS
- Description: Sends the digest of the Service Request to the AWS KMS Sign operation on the key of the chosen User Role, so the key never leaves KMS, and adds the XML signature with the certificate of that role.

##### Audit Log (`edt.duisSigner.auditLog`)

- Kind: component · Colour: cswNew
- Summary: One audit event per signing decision
- Description: Writes one ECS (Elastic Common Schema) event per decision, signed or refused with the reason.

##### Response Endpoint (`edt.responseListener.responseEndpoint`)

- Kind: component · Colour: cswReused
- Summary: Receives Responses and DCC Alerts
- Description: HTTP endpoint for DUIS Responses and DCC Alerts. Answers 500 when Temporal is unavailable, so the message is delivered again.

##### DUIS Library (`edt.responseListener.responseDuisLibrary`)

- Kind: component · Colour: cswReused
- Summary: DUIS XML, schema and signature checks
- Description: Reused from the DCC Adapter: reads the Request ID and codes of each message and checks its DSP XML signature with the trusted DSP certificate.

##### Workflow Signaller (`edt.responseListener.workflowSignaller`)

- Kind: component · Colour: cswNew
- Summary: Signals the waiting Service Request workflow
- Description: New for the EDT, in place of the DCC Adapter database: signals the Service Request workflow whose ID is derived from the Request ID, with the Response or the DCC Alert.

### Interfaces

| Id | Title | Technology | Description |
|---|---|---|---|
| `dcc.dspGatewayProvHttp` | HTTP (Send Service Request) | Sends a Service Request to the DSP | Plain HTTP endpoint that takes one signed DUIS (DCC User Interface Specification) Service Request, sends it to the DSP over HTTPS and returns the synchronous DUIS Response in the HTTP reply. |
| `dcc.dspGatewayReqHttp` | HTTP (Responses) | Passes Responses and Alerts on | Plain HTTP calls with which the gateway passes each DUIS (DCC User Interface Specification) Response and DCC Alert from the DSP on to the Response Listener. |
| `dcc.temporalServerProvGrpc` | gRPC (Temporal) | Starts, signals and runs workflows | Temporal frontend gRPC service on port 7233: workers poll their task queues on it, clients start and signal workflows, and the Temporal UI lists and cancels them. |
| `dcc.kafkaProv` | Kafka | Receives the step, Flow and Batch results | Kafka topics, on the TLS broker port, to which the Orchestrator publishes the step events, the device Flow results and the Batch results, authenticated by mutual TLS (to be confirmed with DCC). |
| `dcc.kmsProvHttps` | HTTPS (KMS) | Signs and verifies with the keys it holds | AWS KMS (Key Management Service) API over HTTPS with TLS 1.3, or TLS 1.2 where the service does not support it: Sign with a User Role key and Verify with the Manifest signing key. |
| `dcc.manifestBucketProvHttps` | HTTPS (S3) | Manifest objects and their signatures | Amazon S3 API over HTTPS: get the Manifest object, its tags and byte ranges of it. |
| `dcc.manifestEventsProvHttps` | HTTPS (SQS) | Object created events | Amazon SQS API over HTTPS: receive and delete the object created messages of the Manifest Bucket. |
| `dcc.certificateStoreProvFile` | File (Certificates) | Certificates mounted as files | The certificate store and its password, mounted read-only as files. |
| `dcc.logAgentReqFile` | File (Logs) | Reads the local logs of each container | Reads the local log files of each EDT container. |
| `dcc.monitoringReqHttp` | HTTP (Metrics) | Scrapes health and metrics | HTTP calls to the health and Prometheus metrics endpoints of each EDT container. |
| `edt.batchIngestReqSqs` | HTTPS (SQS) | Takes Manifest events | Amazon SQS receive and delete calls on the Manifest Events queue. |
| `edt.batchIngestReqS3` | HTTPS (S3) | Reads the Manifest and its signature | Amazon S3 get calls for the Manifest and its signature. |
| `edt.batchIngestReqKms` | HTTPS (KMS) | Checks Manifest signatures | AWS KMS (Key Management Service) Verify calls for the Manifest signatures. |
| `edt.batchIngestReqGrpc` | gRPC (Temporal) | Starts Batch workflows | Temporal client calls that start the Batch workflow of each valid Manifest. |
| `edt.batchIngestProvFile` | File (Logs) | Local ECS JSON logs | Local log files in ECS (Elastic Common Schema) JSON: receipt of each Manifest and its outcome. |
| `edt.batchIngestProvHttp` | HTTP (Health, Metrics) | Health and Prometheus metrics | Health endpoints and Prometheus metrics (JVM and Temporal SDK). |
| `edt.orchestratorReqGrpc` | gRPC (Temporal) | Runs the workflows and activities | Temporal worker connection: polls the workflow and activity task queues and records the progress of each workflow. |
| `edt.orchestratorReqS3` | HTTPS (S3) | Reads the Manifest rows | Amazon S3 byte-range reads of the rows of each Page. |
| `edt.orchestratorReqHttp` | HTTP (DSP Gateway) | Sends signed Service Requests | Posts each signed Service Request over plain HTTP to the DSP HTTPS Gateway and reads the synchronous DUIS Response from the reply. |
| `edt.orchestratorReqKafka` | Kafka | Publishes steps and results | Kafka producer of the step events, the device Flow results and the Batch results, over mutual TLS with the client certificate of the edt-kafka-client Secret (to be confirmed with DCC). |
| `edt.orchestratorReqFile` | File (Certificates) | Reads the DSP certificate | Reads the trusted DSP signing certificate from the certificate store. |
| `edt.orchestratorProvFile` | File (Logs) | Local ECS JSON logs | Local log files in ECS (Elastic Common Schema) JSON: each Service Request sent, each Response decoded, each Flow and Batch finished. |
| `edt.orchestratorProvHttp` | HTTP (Health, Metrics) | Health and Prometheus metrics | Health endpoints and Prometheus metrics (JVM and Temporal SDK). |
| `edt.duisSignerReqGrpc` | gRPC (Temporal) | Takes signing activities | Temporal worker connection: polls the rate-limited signing task queue. |
| `edt.duisSignerReqKms` | HTTPS (KMS) | Signs with the User Role keys | AWS KMS (Key Management Service) Sign calls with the key of the chosen User Role. |
| `edt.duisSignerReqFile` | File (Certificates) | Reads the User Role certificates | Reads the certificates of the two User Roles from the certificate store. |
| `edt.duisSignerProvFile` | File (Logs) | Local ECS JSON audit logs | Local log files in ECS (Elastic Common Schema) JSON, with one audit event per signing decision. |
| `edt.duisSignerProvHttp` | HTTP (Health, Metrics) | Health and Prometheus metrics | Health endpoints and Prometheus metrics (JVM and Temporal SDK). |
| `edt.responseListenerProvHttp` | HTTP (Receive Response) | Receives Responses and DCC Alerts | Plain HTTP endpoint on which the DSP HTTPS Gateway posts each DUIS Response and DCC Alert. |
| `edt.responseListenerReqGrpc` | gRPC (Temporal) | Signals the Service Request workflows | Temporal client calls that signal the Service Request workflow named by the Request ID. |
| `edt.responseListenerReqFile` | File (Certificates) | Reads the DSP certificate | Reads the trusted DSP signing certificate from the certificate store. |
| `edt.responseListenerProvFile` | File (Logs) | Local ECS JSON logs | Local log files in ECS (Elastic Common Schema) JSON: each Response and DCC Alert received, and each security event. |
| `edt.responseListenerProvHttpMetrics` | HTTP (Health, Metrics) | Health and Prometheus metrics | Health endpoints and Prometheus metrics (JVM and Temporal SDK). |

## 3. Relationships

### From DS&A Operator (`operator`)

- → Manifest Bucket (`dcc.manifestBucket`) · kind: flow · "upload signed Manifests (bulk files)" · HTTPS · S3
- → Temporal UI (`dcc.temporalUi`) · kind: flow · "Batch status and results, cancel" · HTTPS

### From Manifest Bucket (`dcc.manifestBucket`)

- → Manifest Events (`dcc.manifestEvents`) · kind: flow · "object created events" · EventBridge

### From Temporal UI (`dcc.temporalUi`)

- → DCC Identity Provider (`idp`) · kind: flow · "operator sign-in" · OIDC
- → Temporal Server (`dcc.temporalServer`) · kind: temporal · "list, query, cancel workflows" · gRPC · :7233

### From Batch Ingest (`edt.batchIngest`)

- → Manifest Events (`dcc.manifestEvents`) · kind: flow · "take Manifest events" · HTTPS · SQS
- → Manifest Bucket (`dcc.manifestBucket`) · kind: flow · "Manifest and signature" · HTTPS · S3
- → AWS KMS (`dcc.kms`) · kind: flow · "Verify the Manifest signature" · HTTPS · KMS API
- → Temporal Server (`dcc.temporalServer`) · kind: temporal · "start Batch workflow" · gRPC · :7233

### From Orchestrator (`edt.orchestrator`)

- → Temporal Server (`dcc.temporalServer`) · kind: temporal · "workflow and activity tasks" · gRPC · :7233
- → Manifest Bucket (`dcc.manifestBucket`) · kind: flow · "Manifest rows (byte ranges)" · HTTPS · S3
- → DSP HTTPS Gateway (`dcc.dspGateway`) · kind: duis · "signed Service Request; synchronous DUIS Response" · HTTP · DUIS
- → Kafka (`dcc.kafka`) · kind: kafka · "step events, device Flow and Batch results" · Kafka
- → Certificate Store (`dcc.certificateStore`) · kind: flow · "DSP certificate" · File

### From DUIS Signer (`edt.duisSigner`)

- → Temporal Server (`dcc.temporalServer`) · kind: temporal · "signing activity tasks" · gRPC · :7233
- → AWS KMS (`dcc.kms`) · kind: flow · "Sign with the User Role key" · HTTPS · KMS API
- → Certificate Store (`dcc.certificateStore`) · kind: flow · "User Role certificates" · File

### From DSP HTTPS Gateway (`dcc.dspGateway`)

- → DSP (`dsp`) · kind: duis · "Service Requests; retries transient errors" · HTTPS · DCCKI TLS
- → Response Listener (`edt.responseListener`) · kind: duis · "Responses and DCC Alerts" · HTTP · DUIS

### From DSP (`dsp`)

- → DSP HTTPS Gateway (`dcc.dspGateway`) · kind: duis · "Responses and DCC Alerts" · HTTPS
- → Smart Metering Devices (`devices`) · kind: flow · "commands and responses" · SM WAN

### From Response Listener (`edt.responseListener`)

- → Temporal Server (`dcc.temporalServer`) · kind: temporal · "signal the Service Request workflow" · gRPC · :7233
- → Certificate Store (`dcc.certificateStore`) · kind: flow · "DSP certificate" · File

### From Log Agent (`dcc.logAgent`)

- → Enhanced Diagnostics Tool (`edt`) · kind: flow · "read the local ECS JSON logs of every container" · File
- → CloudWatch Logs (`dcc.cloudWatch`) · kind: flow · "send the logs" · HTTPS · CloudWatch Logs

### From Monitoring (`dcc.monitoring`)

- → Enhanced Diagnostics Tool (`edt`) · kind: flow · "scrape health and Prometheus metrics of every container" · HTTP

## 4. What each view shows

### 200 - Components / 200.010 - Batch Ingest

Elements: `edt.batchIngest`, `edt.batchIngest.eventConsumer`, `edt.batchIngest.manifestVerifier`, `edt.batchIngest.batchStarter`, `dcc.temporalServer`, `dcc.manifestEvents`, `dcc.manifestBucket`, `dcc.kms`, `dcc.logAgent`, `dcc.monitoring`, `dcc.logAgentReqFile`, `dcc.monitoringReqHttp`, `edt.batchIngestReqGrpc`, `edt.batchIngestReqSqs`, `edt.batchIngestReqS3`, `edt.batchIngestReqKms`, `edt.batchIngestProvFile`, `edt.batchIngestProvHttp`, `dcc.temporalServerProvGrpc`, `dcc.manifestEventsProvHttps`, `dcc.manifestBucketProvHttps`, `dcc.kmsProvHttps`

Edges:
- gRPC (Temporal) → gRPC (Temporal)
- Temporal Server → gRPC (Temporal)
- HTTPS (SQS) → HTTPS (SQS)
- Manifest Events → HTTPS (SQS)
- HTTPS (S3) → HTTPS (S3)
- Manifest Bucket → HTTPS (S3)
- HTTPS (KMS) → HTTPS (KMS)
- AWS KMS → HTTPS (KMS)
- Log Agent → File (Logs)
- File (Logs) → File (Logs)
- Monitoring → HTTP (Metrics)
- HTTP (Metrics) → HTTP (Health, Metrics)
- Batch Ingest → gRPC (Temporal)
- Batch Ingest → HTTPS (SQS)
- Batch Ingest → HTTPS (S3)
- Batch Ingest → HTTPS (KMS)
- Batch Ingest → File (Logs)
- Batch Ingest → HTTP (Health, Metrics)

### 100 - Component Model / 100.010 - Containers

Elements: `dsp`, `operator`, `dcc`, `dcc.monitoring`, `devices`, `edt`, `edt.batchIngest`, `edt.orchestrator`, `edt.duisSigner`, `edt.responseListener`, `dcc.manifestBucket`, `dcc.temporalUi`, `dcc.kms`, `dcc.kafka`, `dcc.dspGateway`, `dcc.logAgent`, `dcc.manifestEvents`, `idp`, `dcc.temporalServer`, `dcc.cloudWatch`

Edges:
- DSP → Smart Metering Devices: commands and responses
- DS&A Operator → Manifest Bucket: upload signed Manifests (bulk files)
- Batch Ingest → Manifest Bucket: Manifest and signature
- Orchestrator → Manifest Bucket: Manifest rows (byte ranges)
- Batch Ingest → Manifest Events: take Manifest events
- Manifest Bucket → Manifest Events: object created events
- DS&A Operator → Temporal UI: Batch status and results, cancel
- Temporal UI → DCC Identity Provider: operator sign-in
- Batch Ingest → Temporal Server: start Batch workflow
- Orchestrator → Temporal Server: workflow and activity tasks
- DUIS Signer → Temporal Server: signing activity tasks
- Response Listener → Temporal Server: signal the Service Request workflow
- Temporal UI → Temporal Server: list, query, cancel workflows
- Batch Ingest → AWS KMS: Verify the Manifest signature
- DUIS Signer → AWS KMS: Sign with the User Role key
- Orchestrator → Kafka: step events, device Flow and Batch results
- DSP → DSP HTTPS Gateway: Responses and DCC Alerts
- Orchestrator → DSP HTTPS Gateway: signed Service Request; synchronous DUIS Response
- DSP HTTPS Gateway → DSP: Service Requests; retries transient errors
- DSP HTTPS Gateway → Response Listener: Responses and DCC Alerts
- Log Agent → CloudWatch Logs: send the logs
- Log Agent → Enhanced Diagnostics Tool: read the local ECS JSON logs of every container
- Monitoring → Enhanced Diagnostics Tool: scrape health and Prometheus metrics of every container

### 200 - Components / 200.030 - DUIS Signer

Elements: `edt.duisSigner`, `edt.duisSigner.signActivity`, `edt.duisSigner.signingPolicy`, `edt.duisSigner.kmsSigner`, `edt.duisSigner.auditLog`, `dcc.temporalServer`, `dcc.kms`, `dcc.certificateStore`, `dcc.logAgent`, `dcc.monitoring`, `dcc.logAgentReqFile`, `dcc.monitoringReqHttp`, `edt.duisSignerReqGrpc`, `edt.duisSignerReqKms`, `edt.duisSignerReqFile`, `edt.duisSignerProvFile`, `edt.duisSignerProvHttp`, `dcc.temporalServerProvGrpc`, `dcc.kmsProvHttps`, `dcc.certificateStoreProvFile`

Edges:
- gRPC (Temporal) → gRPC (Temporal)
- Temporal Server → gRPC (Temporal)
- HTTPS (KMS) → HTTPS (KMS)
- AWS KMS → HTTPS (KMS)
- File (Certificates) → File (Certificates)
- Certificate Store → File (Certificates)
- Log Agent → File (Logs)
- File (Logs) → File (Logs)
- Monitoring → HTTP (Metrics)
- HTTP (Metrics) → HTTP (Health, Metrics)
- DUIS Signer → gRPC (Temporal)
- DUIS Signer → HTTPS (KMS)
- DUIS Signer → File (Certificates)
- DUIS Signer → File (Logs)
- DUIS Signer → HTTP (Health, Metrics)

### 200 - Components / 200.020 - Orchestrator

Elements: `edt.orchestrator`, `edt.orchestrator.batchWorkflow`, `edt.orchestrator.pageWorkflow`, `edt.orchestrator.flowWorkflow`, `edt.orchestrator.serviceRequestWorkflow`, `edt.orchestrator.manifestReader`, `edt.orchestrator.flowCatalogue`, `edt.orchestrator.requestBuilder`, `edt.orchestrator.duisSender`, `edt.orchestrator.duisLibrary`, `edt.orchestrator.responseDecoder`, `edt.orchestrator.resultPublisher`, `dcc.temporalServer`, `dcc.manifestBucket`, `dcc.dspGateway`, `dcc.kafka`, `dcc.certificateStore`, `dcc.logAgent`, `dcc.monitoring`, `dcc.logAgentReqFile`, `dcc.monitoringReqHttp`, `edt.orchestratorReqGrpc`, `edt.orchestratorReqS3`, `edt.orchestratorReqHttp`, `edt.orchestratorReqKafka`, `edt.orchestratorReqFile`, `edt.orchestratorProvFile`, `edt.orchestratorProvHttp`, `dcc.temporalServerProvGrpc`, `dcc.manifestBucketProvHttps`, `dcc.dspGatewayProvHttp`, `dcc.kafkaProv`, `dcc.certificateStoreProvFile`

Edges:
- gRPC (Temporal) → gRPC (Temporal)
- Temporal Server → gRPC (Temporal)
- HTTPS (S3) → HTTPS (S3)
- Manifest Bucket → HTTPS (S3)
- HTTP (DSP Gateway) → HTTP (Send Service Request)
- DSP HTTPS Gateway → HTTP (Send Service Request)
- Kafka → Kafka
- Kafka → Kafka
- File (Certificates) → File (Certificates)
- Certificate Store → File (Certificates)
- Log Agent → File (Logs)
- File (Logs) → File (Logs)
- Monitoring → HTTP (Metrics)
- HTTP (Metrics) → HTTP (Health, Metrics)
- Orchestrator → gRPC (Temporal)
- Orchestrator → HTTPS (S3)
- Orchestrator → HTTP (DSP Gateway)
- Orchestrator → Kafka
- Orchestrator → File (Certificates)
- Orchestrator → File (Logs)
- Orchestrator → HTTP (Health, Metrics)

### 200 - Components / 200.040 - Response Listener

Elements: `edt.responseListener`, `edt.responseListener.responseEndpoint`, `edt.responseListener.responseDuisLibrary`, `edt.responseListener.workflowSignaller`, `dcc.temporalServer`, `dcc.dspGateway`, `dcc.certificateStore`, `dcc.logAgent`, `dcc.monitoring`, `dcc.dspGatewayReqHttp`, `dcc.logAgentReqFile`, `dcc.monitoringReqHttp`, `edt.responseListenerReqGrpc`, `edt.responseListenerReqFile`, `edt.responseListenerProvHttp`, `edt.responseListenerProvFile`, `edt.responseListenerProvHttpMetrics`, `dcc.temporalServerProvGrpc`, `dcc.certificateStoreProvFile`

Edges:
- gRPC (Temporal) → gRPC (Temporal)
- Temporal Server → gRPC (Temporal)
- DSP HTTPS Gateway → HTTP (Responses)
- HTTP (Responses) → HTTP (Receive Response)
- File (Certificates) → File (Certificates)
- Certificate Store → File (Certificates)
- Log Agent → File (Logs)
- File (Logs) → File (Logs)
- Monitoring → HTTP (Metrics)
- HTTP (Metrics) → HTTP (Health, Metrics)
- Response Listener → gRPC (Temporal)
- Response Listener → HTTP (Receive Response)
- Response Listener → File (Certificates)
- Response Listener → File (Logs)
- Response Listener → HTTP (Health, Metrics)
