# EDT (learning project)

A learning version of the Enhanced Diagnostics Tool: it runs diagnostic Flows of DUIS Service Requests
on smart-metering devices, using Temporal. Not meant for production.

Architecture reference: `architecture-model.md` and `architecture-model-sequences-deployment.md`.

## Build

```
./gradlew build
```

Needs Java 25. Gradle is downloaded by the wrapper.

## Steps

### Step 1: the build

| File | What it does |
|---|---|
| `gradlew`, `gradle/wrapper/` | The Gradle wrapper. Pins Gradle 9.9.0, so everyone builds with the same version. |
| `settings.gradle` | Names the project and lists the applications (`include`). |
| `build.gradle` | Settings shared by all applications: Java 25 toolchain, Maven Central. |
| `gradle/libs.versions.toml` | Version catalogue: all versions in one place (Quarkus 3.40.1.2, Temporal SDK 1.40.0). |
| `.gitattributes` | Keeps `gradlew` with Unix line endings. |

Key ideas:
- **Toolchain**: Gradle compiles with Java 25 even if another JDK is active.
- **Catalogue**: build files use `libs.temporal.sdk` instead of repeating version numbers.

### Step 2: first application, `duis-signer`

| File | What it does |
|---|---|
| `duis-signer/build.gradle` | Applies the Quarkus plugin and imports the Quarkus BOM. |
| `duis-signer/src/main/resources/application.properties` | Quarkus configuration (for now only the app name). |
| `duis-signer/src/main/java/prepare/edt/duissigner/StartupLog.java` | Placeholder bean that logs a line at startup. |

Key ideas:
- **BOM** (`enforcedPlatform`): fixes the version of every Quarkus extension, so they are never mixed.
- **CDI** (`quarkus-arc`): Quarkus creates `@ApplicationScoped` beans and calls `@Observes StartupEvent` methods at startup.
- A Quarkus app keeps running until stopped, even with no HTTP server.

Run it:
```
./gradlew :duis-signer:quarkusDev                      # dev mode, live reload
./gradlew :duis-signer:build                           # package
java -jar duis-signer/build/quarkus-app/quarkus-run.jar
```

### Step 3: health and metrics

Added `quarkus-smallrye-health` and `quarkus-micrometer-registry-prometheus` to `duis-signer`. Port set in
`application.properties` (`quarkus.http.port=8083`).

| Endpoint | Used for |
|---|---|
| `/q/health/live` | Liveness: is the process alive? If not, Kubernetes restarts it. |
| `/q/health/ready` | Readiness: can it take work? If not, it gets no traffic. |
| `/q/health` | Both together. |
| `/q/metrics` | Prometheus metrics (JVM memory, threads, ...), scraped by monitoring. |

Key ideas:
- No checks are registered yet, so health is always `UP`. A check for the Temporal connection comes later.
- Ports: batch-ingest 8081, orchestrator 8082, duis-signer 8083, response-listener 8084 (the Temporal UI is on 8088).

### Step 4: structured logging (ECS JSON)

Added `quarkus-logging-json`, configured in `application.properties`:
- Packaged app (`prod`): one JSON object per line, ECS format. This is what the log agent sends to CloudWatch.
- Dev and test: plain text, easier to read.

Structured logging means the data goes into **fields**, not into the message text.
`LogFields` puts key-value fields into the MDC for the lines written inside a `try` block:

```java
try (var fields = LogFields.of("event.action", "startup")) {
    LOG.info("DUIS Signer started");
}
```

Output in JSON (shortened):
```
{"@timestamp":"...","log.level":"INFO","message":"DUIS Signer started","event.action":"startup","service.name":"duis-signer"}
```

Output in dev:
```
16:02:30 INFO  [pr.ed.du.StartupLog] DUIS Signer started {event.action=startup}
```

Key ideas:
- **MDC** (Mapped Diagnostic Context): fields attached to the current thread, added to every log line until removed.
  `LogFields` removes them when the `try` block ends.
- Custom fields are written at the top level of the JSON line, under their own name (`event.action`), because
  `quarkus.log.console.json.mdc.flat-fields=true` is set in each app. There is no nested `mdc` object. Temporal adds its own
  keys the same way (`WorkflowId`, `RunId`, `ActivityType`, ...).
- A key appears at most once in a line. `error.message` and `error.type` come from one source per line: either the formatter
  writes them for a logged exception, or the code sets them as fields, never both.
- Each app has a `LoggingConfigTest` that fails if the JSON, ECS or flat-fields setting is removed, or if dev and test stop
  being plain text.

Check the real logs on the cluster (needs `deploy/edt.sh up` first):

```bash
deploy/check-log-shape.sh
```

It reads the logs of the four apps and fails (exit 1) if a line is not one JSON object, lacks `@timestamp`, `log.level`,
`message`, `service.name` or `ecs.version`, has an `mdc` object, repeats a key, or is on `edt.interaction` and lacks `event.action`, `event.outcome`, `edt.interface` or `network.direction`. For each app it prints how many lines it read
and, for each kind of problem, how many lines have it and the first one. `EDT_LOG_TAIL` sets how many lines are read per app
(default 2000), `EDT_NAMESPACE` the namespace (default `edt`). Run it after any change to logging: the unit tests check the
settings, this checks what the packaged apps really write.
- Temporal workflow code needs its own logger (`Workflow.getLogger`), because workflows are replayed. Comes later.

#### One line for each boundary crossing: the logger `edt.interaction`

Each time a container calls another system, or is called by one, through an interface of the architecture model, it writes one
INFO line on the logger `edt.interaction` (`log.logger` in the JSON). Searching that one logger shows everything a container
exchanged with the outside. The `Interaction` helper, next to `LogFields` in each app, writes the line:

```java
try (var call = Interaction.outbound("edt.orchestratorReqHttp", "service-request-sent")
        .with("edt.request_id", requestId)) {
    // ... make the call ...
    call.log("Service Request posted to the DSP HTTPS Gateway");
}
```

Every interaction line has these keys:

| Key | Value |
|---|---|
| `event.action` | what happened, for example `manifest-read`, `service-request-sent` |
| `event.outcome` | `success` or `failure` |
| `edt.interface` | the ID of the interface in the model, for example `edt.orchestratorReqHttp` |
| `network.direction` | `outbound` for a call the container makes, `inbound` for a call it receives |
| `event.duration` | nanoseconds, for a call the container makes (and for the Response Listener's handling of a message) |

Other keys are identifiers and results only (`edt.batch_id`, `edt.request_id`, `http.response.status_code`, sizes): never the body
of a Service Request or Response, a Manifest row, a signature, a key or the content of a certificate. A poll of the queue that
returns nothing writes no line. The audit line of the DUIS Signer stays on `edt.audit` (it also has `edt.interface` and
`event.duration`), and security alerts stay on `edt.security`: both must survive turning `edt.interaction` down.

To turn the interaction lines down without a code change, set the level in `application.properties` (or with the environment
variable `QUARKUS_LOG_CATEGORY__EDT_INTERACTION__LEVEL=WARN`). The default, INFO, is set in each app and checked by `LoggingConfigTest`.

The ID of a Batch is `edt.batch_id` in every container (Batch Ingest used to write `edt.batch_workflow_id`).

**Exceptions.** A line that reports an unexpected technical failure passes the exception to the logger, so the JSON has
`error.type`, `error.message` and `error.stack_trace` (`manifest-poll-failed`, `manifest-ingest-failed`,
`dsp-message-not-delivered`). A line that reports an expected rejection of outside input keeps the reason in `error.message` and
has no stack trace, because an outsider can cause it at will (`manifest-event-unreadable`, `manifest-signature-invalid`,
`manifest-row-invalid`, `response-not-decodable`, `dsp-signature-invalid`, `dsp-message-unreadable`).

**One logging API.** All code logs through SLF4J (`org.slf4j.Logger`, `org.slf4j.MDC`); workflows use `Workflow.getLogger`. Each
app's `ArchitectureTest` fails if a class depends on `org.jboss.logging`.

Check the interaction lines on the cluster, after a one-device Manifest has been processed:

```bash
deploy/check-log-shape.sh        # every edt.interaction line has the four keys
deploy/check-interactions.sh     # every interface a one-device run uses has a line, per container
deploy/test-check-logs.sh        # tests both scripts against made-up logs (no cluster)
```

`EDT_LOG_FIXTURE_DIR=<dir>` makes the first two read `<dir>/<app>.log` instead of the cluster.

### Step 5: Clean Architecture packages and the architecture test

Each application has four packages. Dependencies point inward only:

```
infrastructure, adapter  ->  usecase  ->  entity
```

| Package | Contains | May use frameworks? |
|---|---|---|
| `entity` | Core concepts (a signing decision, a User Role) | No |
| `usecase` | Application logic, plus interfaces for what it needs from outside | No |
| `adapter` | Implements those interfaces (Temporal activity, KMS, audit log) | Yes |
| `infrastructure` | Wiring and helpers (startup, CDI producers, `LogFields`) | Yes |

`ArchitectureTest` (ArchUnit 1.5.1) fails the build if:
- `entity` uses `usecase`, `adapter` or `infrastructure`,
- `usecase` uses `adapter` or `infrastructure`,
- `entity` or `usecase` uses Quarkus, Temporal, AWS SDK or `jakarta`.

Key ideas:
- A use case says *what* it needs (an interface, for example "sign these bytes"); an adapter says *how* (KMS).
  So the signing provider can change without touching the logic.
- Use cases have no CDI annotations: `infrastructure` creates them with producer methods.
- `allowEmptyShould(true)` is temporary: `entity` and `usecase` are still empty.
- Run the tests: `./gradlew :duis-signer:test`

### Step 6: the other three applications

`batch-ingest`, `orchestrator` and `response-listener` copy the structure of `duis-signer`:
same build file, the four packages, their own `LogFields` and `ArchitectureTest`.
Each keeps its own copy on purpose: the applications share no code, like separate services in production.

| Application | Port | Package |
|---|---|---|
| `batch-ingest` | 8081 | `prepare.edt.batchingest` |
| `orchestrator` | 8082 | `prepare.edt.orchestrator` |
| `duis-signer` | 8083 | `prepare.edt.duissigner` |
| `response-listener` | 8084 | `prepare.edt.responselistener` |

Build and test all: `./gradlew build`

### Step 7: containers

Each application has a `Dockerfile` and a `.dockerignore`. The root `docker-compose.yml` runs all four.

```
docker network create edt     # once
./gradlew build               # compile first: the image only copies the result
docker compose up --build -d
docker compose ps             # all four should be "(healthy)"
docker compose logs -f duis-signer
docker compose down
```

| File | What it does |
|---|---|
| `<app>/Dockerfile` | Java 25 runtime (`eclipse-temurin:25-jre`) plus `build/quarkus-app`. Runs as user 1001, not root, with `--enable-native-access=ALL-UNNAMED` so the JVM prints no non-JSON warning. |
| `<app>/.dockerignore` | Sends only `build/quarkus-app/` to Docker. |
| `docker-compose.yml` | The four services, ports 8081-8084, healthchecks, network `edt`. |

Key ideas:
- **Build outside, package inside**: Gradle compiles on your machine, Docker only copies the result.
- **Layers**: `lib/` (dependencies) is copied first, so Docker reuses it when only our code changes.
- **Profiles**: inside the container the app runs with the `prod` profile, so the logs are ECS JSON.
- **Healthcheck**: the image has no `curl`; bash opens a TCP connection to `/q/health/ready` and looks for `"UP"`.
- **Network `edt`**: created once by hand and declared `external`, so this file and `dcc-tools` can share it.

### Step 8: Temporal in `dcc-tools`

`dcc-tools/` holds what DCC provides in production. It has its own `docker-compose.yml`.
First piece: Temporal (versions from Temporal's own compose examples: server 1.32.2, PostgreSQL 16).

```
cd dcc-tools
docker compose up -d
docker compose ps -a        # temporal-schema and temporal-namespace show "Exited (0)": that is success
```
Temporal UI: http://localhost:8088 (port 8080 was already taken on this machine).

| Service | Role |
|---|---|
| `postgresql` | Temporal's database. Not used by our applications. |
| `temporal-schema` | Runs once: creates the databases `temporal` (histories, timers, queues) and `temporal_visibility` (the workflow list the UI shows). |
| `temporal` | The Temporal Server. gRPC on port 7233: workers and clients connect here. |
| `temporal-namespace` | Runs once: creates the namespace `edt`. |
| `temporal-ui` | Web UI: namespaces, workflows, their histories. |

Files: `dcc-tools/temporal/scripts/` (the two one-shot scripts), `dcc-tools/temporal/dynamicconfig/` (server settings).

Key ideas:
- **Temporal Server stores state, it runs no code.** Our applications are the *workers*: they poll task queues
  on the server, run workflow and activity code, and report the results back.
- **Namespace**: an isolated space for workflows (own retention, own search attributes). The EDT uses `edt`.
- **Start order**: `depends_on` with `service_healthy` and `service_completed_successfully` makes compose
  wait for the database, then the schema, then the server.
- Inside the `edt` network the server is `temporal:7233`; from your machine it is `localhost:7233`.

### Step 9: `duis-signer` becomes a Temporal worker

The signer connects to Temporal at startup and polls the task queue `duis-signing`.

| File | What it does |
|---|---|
| `infrastructure/TemporalConfig.java` | Reads `edt.temporal.target`, `namespace`, `task-queue` from `application.properties`. |
| `infrastructure/TemporalConnection.java` | CDI producers: the gRPC connection (`WorkflowServiceStubs`) and the `WorkflowClient` for namespace `edt`. |
| `infrastructure/TemporalWorker.java` | At startup: creates the worker, registers the Sign activity, starts polling. At shutdown: stops it. |
| `infrastructure/TemporalReadinessCheck.java` | `/q/health/ready` is `DOWN` when the Temporal Server does not answer. |
| `adapter/SignActivity.java` | The activity interface. Name fixed as `"Sign"`: Temporal matches activities by name. |
| `adapter/SignActivityImpl.java` | Placeholder: refuses every request with a non-retryable failure. |

The worker is on the right side of this picture:

```
orchestrator                    Temporal Server                     duis-signer (worker)
  schedules "Sign"  ------->    task queue "duis-signing"   <-------  polls the queue
                                                                      runs SignActivityImpl
  gets the result   <-------    stores the result           <-------  reports the result
```

Check that the worker is polling:
```
docker run --rm --network edt temporalio/admin-tools:1.32.2 \
  temporal task-queue describe --task-queue duis-signing --task-queue-type activity --namespace edt --address temporal:7233
```

Key ideas:
- **Worker**: a process that polls a task queue and runs the code registered for it. Many workers can poll the same queue.
- **CDI producer** (`@Produces`): creates beans from library classes that have no CDI annotations.
- **Configuration**: `localhost:7233` on your machine; in Docker, `EDT_TEMPORAL_TARGET=temporal:7233` overrides it.
- **Fail fast**: `WorkerFactory.start()` contacts the server. If Temporal is down, the app exits and Docker restarts it
  (`restart: unless-stopped`) until Temporal is up. If Temporal goes down later, the worker reconnects by itself
  and readiness reports `DOWN` meanwhile.
- **Non-retryable failure**: an error Temporal must not retry (retrying would not help).
- The Quarkus BOM forces some of Temporal's libraries to newer versions (protobuf 3.25 to 4.35). It works so far.

Also fixed in all four applications: the text log format now prints exceptions (`%e`).

### Step 10: first round trip, Orchestrator to DUIS Signer

The Orchestrator is now a Temporal worker on task queue `orchestrator`. It runs a throwaway workflow,
`SignProbe`, that calls the Sign activity on the signer's queue `duis-signing`.

| File (in `orchestrator`) | What it does |
|---|---|
| `adapter/SignActivity.java` | The Orchestrator's own **copy** of the signer's activity interface. Same name `"Sign"`. |
| `adapter/SignProbeWorkflow.java`, `SignProbeWorkflowImpl.java` | The workflow: one call to `Sign`, on the signing queue, max 30 s per attempt, max 3 attempts. |
| `infrastructure/TemporalWorker.java` | Registers the workflow with a factory, so the signing queue name comes from configuration. |
| `src/test/.../SignProbeWorkflowTest.java` | Runs the workflow on an in-memory Temporal server with a fake signer. No Docker. |

Try it (with `dcc-tools` running):
```
docker compose up --build -d orchestrator duis-signer
docker run --rm --network edt temporalio/admin-tools:1.32.2 \
  temporal --address temporal:7233 --namespace edt \
  workflow execute --type SignProbe --task-queue orchestrator --workflow-id sign-probe-1 --input '"<request/>"' --input '"ESME"'
```
(`SignProbe` takes two inputs since the requests became real DUIS: the request and the device type, which a DUIS request does not carry.)
It fails on purpose: the signer answers `Signing is not implemented yet` (type `NotImplemented`, non-retryable).
Open it in the Temporal UI (http://localhost:8088) to see the history:

```
WorkflowExecutionStarted       the CLI starts the workflow
WorkflowTask...                the Orchestrator runs the workflow code: it asks for "Sign"
ActivityTaskScheduled          "Sign" goes to queue duis-signing
ActivityTaskStarted/Failed     the signer runs it and refuses
WorkflowTask...                the Orchestrator runs the workflow code again: the error is not caught
WorkflowExecutionFailed
```

Key ideas:
- **Workflow vs activity**: the workflow decides (deterministic code, replayed from history); the activity does the work.
- **Determinism**: no I/O, clock, random or threads in workflow code. Use `Workflow.getLogger` for logs.
- **Workflow factory**: Temporal creates workflow objects, not CDI, so configuration is passed in through a factory.
- **Retries**: set per activity call. A non-retryable failure is never retried (the test checks it).
- **Event history**: everything that happened, stored by the Temporal Server. It is how Temporal recovers after a crash.

### Step 11a: signing logic, the core (entities and use case)

Only plain Java: no Quarkus, Temporal, XML or keys. The outside world is reached through **ports** (interfaces).

```
SignServiceRequest (use case)
  1. ServiceRequestReader.read(xml)    port: parse XML, check schema      -> adapter in 11b
  2. SigningPolicy.decide(request)     entity: role, or refuse
  3. Signer.sign(xml, role)            port: sign with the role's key     -> adapters in 11b (software), 11c (KMS)
  4. AuditLog.record(event)            port: one event per decision       -> adapter in 11d
```

| File (in `duis-signer`) | What it is |
|---|---|
| `entity/UserRole`, `ServiceRequest`, `SigningDecision` | The core concepts. |
| `entity/SigningPolicy` | The rule: SRV allowlist, role choice (Other User when both may), originator, device type. |
| `usecase/SignServiceRequest` | Runs the 4 steps. Returns `Signed` or `Refused`. |
| `usecase/ServiceRequestReader`, `Signer`, `AuditLog` | The ports. |
| `src/test/.../SigningPolicyTest`, `SignServiceRequestTest` | Plain JUnit with hand-written fakes. |

Rules the tests check:
- A refused request never reaches the signer.
- Every decision (signed or refused) is audited.
- If the audit fails, no signed request is returned.
- A technical error (signing service down) propagates, so Temporal can retry. It is not audited: it is not a decision.

Key ideas:
- **Ports and adapters**: the use case declares what it needs; adapters (outer layers) provide it.
- **Sealed interfaces**: `SigningDecision` and `SignResult` have a fixed set of cases; `switch` must handle them all.
- **PLACEHOLDER data**: SRV codes, User IDs and allowlists in the tests are invented until DCC provides the real ones.
- **To confirm with DCC**: the role is chosen from the SRV, then the originator must be that role's User ID.
- `allowEmptyShould` is gone from the signer's architecture test: its inner layers now have classes.

### Step 11b: XML reading and JCA signing (software keys)

Two adapters for the ports of step 11a, in `duis-signer/.../adapter`:

| File | What it does |
|---|---|
| `XmlServiceRequestReader` | Parses safely (no DTD, so no XXE), validates against `schema/service-request.xsd` (a **partial** schema written from the released DUIS 5.5 examples, **not** the DUIS XSD), reads the Request ID, the SRV and the originator (the first part of the Request ID). The device type is not in a DUIS request: the caller gives it (see "Real DUIS Service Request" below). |
| `JcaXmlSigner` | Adds an enveloped XML signature: ECDSA P-256 + SHA-256, with the role's certificate in `KeyInfo`. Keys from a JCA `KeyStore`, signature from a JCA provider chosen by name. |
| `SecureXml` | Safe parse and exact write of XML (shared by both). |

Signed result (shortened):
```xml
<sr:Request xmlns:sr="http://www.dccinterface.co.uk/ServiceUserGateway" schemaVersion="5.5">
  <sr:Header>...</sr:Header><sr:Body>...</sr:Body>
  <Signature xmlns="http://www.w3.org/2000/09/xmldsig#">
    <SignedInfo>
      <CanonicalizationMethod Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#"/>
      <SignatureMethod Algorithm="http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha256"/>
      <Reference URI="">  <!-- the whole document, minus this Signature (enveloped) -->
        <DigestValue>...</DigestValue>
      </Reference>
    </SignedInfo>
    <SignatureValue>...</SignatureValue>  <!-- 64 bytes: r||s -->
    <KeyInfo><X509Data><X509Certificate>...</X509Certificate></X509Data></KeyInfo>
  </Signature>
</sr:Request>
```

Tests (no Docker): keys made with `keytool` (EC `secp256r1`) into a PKCS#12 file; sign, then verify with the
role's certificate; verify fails with the other role's certificate and after changing one value;
a spy provider proves the configured provider does the signing.

#### JCA in short

- **Engine classes** (`Signature`, `KeyStore`, `KeyPairGenerator`, ...) are the API. You never create them with `new`:
  `Signature.getInstance("SHA256withECDSA", "SunEC")`.
- **Providers** supply the implementations. A provider is a list of `engine.algorithm = class` entries
  (see `SpyProvider` in the tests). Each class extends the engine's **SPI** (`SignatureSpi`, `KeyStoreSpi`, ...).
- `getInstance(alg)` takes the first installed provider that has it. `getInstance(alg, provider)` forces one.
  We always name the provider, from configuration.
- **Opaque keys**: a `PrivateKey` may be just a handle (KMS, HSM). The code does not change: only the provider does.

| Setup | KeyStore type | Signature provider | Where the private key is |
|---|---|---|---|
| Tests (now) | `PKCS12` | `SunEC` (JDK) | in the `.p12` file |
| Local with Floci (11c) | `KMS` (our provider) | our KMS provider | inside KMS (Floci) |
| Maybe later | vendor type | CloudHSM provider | inside the HSM |

How the provider reaches the XML signature: the JDK's XML DSig reads the sign-context property
`org.jcp.xml.dsig.internal.dom.SignatureProvider` and calls `Signature.getInstance(alg, provider)`.
It also converts the ECDSA signature from DER (what JCA returns) to r||s (what XML DSig needs).

### Step 11c-1: KMS keys in Floci, and the certificate store

`dcc-tools` now has **Floci** (open-source AWS emulator, MIT, no account) and its own Gradle build with one tool, `kms-setup`.

```
cd dcc-tools
./gradlew :kms-setup:installDist     # build the tool (separate build, own wrapper)
docker compose up -d --build         # Floci starts, then kms-setup runs once and exits
ls certs/                            # certificate-store.p12 (git-ignored, regenerated at every start)
```

`kms-setup` plays DCC's part (`dcc-tools/kms-setup/.../KmsSetup.java`):
1. For each User Role, a KMS key `ECC_NIST_P256` / `SIGN_VERIFY`, alias `alias/edt-other-user` and
   `alias/edt-registered-supplier-agent`. The private key is created inside KMS and never leaves it.
2. A test CA, in memory only.
3. For each role, a certificate holding the **KMS public key**, signed by the test CA.
   A CA only needs the subject's public key, so a key that lives in KMS can be certified.
4. The **certificate store**: PKCS#12, certificates only (no private keys), written with JCA `KeyStore`.

| Service | Role |
|---|---|
| `floci` | AWS API on port 4566 (`floci/floci:2.4.0`). Only KMS is used for now. Keeps everything in memory. |
| `kms-setup` | Runs once at every start: keys, test CA, certificates, `certs/certificate-store.p12`. |

Key ideas:
- **Keys vs certificates**: KMS holds the private keys; the certificate store holds the certificates.
  The model says the same: KMS cannot hold certificates.
- **In memory**: restarting Floci loses the keys; `kms-setup` creates new ones and a new store.
  Restart `duis-signer` afterwards, so it reads the new certificates.
- BouncyCastle is used only here, to build certificates (the JDK has no public API for it). The CA still signs through JCA.

**Why Floci, not moto or LocalStack**: LocalStack now needs an account. moto was tried first, but a probe showed
that KMS `Sign` with `MessageType=DIGEST` gives a wrong signature there (moto hashes the digest again). The model says
the signer sends the **digest**, so the emulator was replaced rather than the design. Floci signs a digest as real
AWS KMS does (checked with the same probe). It also covers S3, SQS and EventBridge, which Batch Ingest needs later.

### Step 11c-2: our own JCA provider, `EDT-KMS`

`duis-signer/.../adapter/kms/` is a small JCA provider. With it, the private keys stay in KMS and
`JcaXmlSigner` (step 11b) works **unchanged**: only the KeyStore type and the provider name differ.

| File | What it is |
|---|---|
| `KmsProvider` | `Provider` named `EDT-KMS`. Registers `KeyStore.KMS` and `Signature.SHA256withECDSA`. |
| `KmsKeyStoreSpi` | `load()` reads the certificate store. `getKey("other-user")` returns a handle to `alias/edt-other-user`. |
| `KmsPrivateKey` | The handle: a KMS key id, no key material (`getEncoded()` is `null`). |
| `KmsSignatureSpi` | Hashes the data with SHA-256 locally, sends the **digest** to KMS `Sign` (`MessageType=DIGEST`), returns the DER signature. |

```java
Security.addProvider(new KmsProvider(kmsClient, "alias/edt-"));       // once, at startup

KeyStore keys = KeyStore.getInstance("KMS", "EDT-KMS");               // instead of "PKCS12"
keys.load(certificateStoreStream, password);
new JcaXmlSigner(keys, null, aliases, "EDT-KMS");                     // instead of "SunEC"
```

The signing path:
```
JcaXmlSigner -> JDK XML DSig -> Signature.getInstance("SHA256withECDSA", "EDT-KMS")
             -> KmsSignatureSpi: SHA-256 of SignedInfo -> KMS Sign(DIGEST) -> DER signature
             -> JDK XML DSig converts DER to r||s -> <SignatureValue>
```

Test: `KmsProviderTest` starts Floci with **Testcontainers** (needs Docker), creates a KMS key and a certificate
for it, then signs XML through `JcaXmlSigner` and verifies it with the certificate.

Key ideas:
- **SPI**: a provider supplies engines by extending `KeyStoreSpi` and `SignatureSpi`. Callers only see `KeyStore` and `Signature`.
- **`Provider.Service`**: each engine is a service whose `newInstance()` creates the SPI, so the provider can hand
  its `KmsClient` to them (no static fields).
- **Opaque key**: a `PrivateKey` can be just a reference. The AWS SDK appears only in `KmsSignatureSpi`.
- **Verify locally**: verifying needs only the public key, so `KmsSignatureSpi` passes it to the JDK (`SunEC`).
- The AWS SDK uses the plain JDK HTTP client (`url-connection-client`); Netty and Apache clients are excluded.

### Step 11d: everything wired, KMS by default

The signer now really signs. `SignProbe` returns a Service Request signed in KMS (Floci).

| File (in `duis-signer`) | What it does |
|---|---|
| `infrastructure/SigningConfig`, `KmsConfig`, `PolicyConfig` | Configuration: KeyStore type and providers, KMS endpoint, allowlists (**PLACEHOLDER**). |
| `infrastructure/SigningWiring` | CDI producer: installs `EDT-KMS` if configured, loads the KeyStore, builds adapters, policy and use case. |
| `adapter/SignActivityImpl`, `SignOutcome` | The Sign activity calls the use case and returns `SignOutcome` (SIGNED or REFUSED). |
| `adapter/EcsAuditLog` | One audit event per decision, logger `edt.audit`. |

Configuration (`duis-signer/src/main/resources/application.properties`):
```
edt.signing.keystore-type=KMS            # PKCS12 for software keys
edt.signing.keystore-provider=EDT-KMS    # SUN for software keys
edt.signing.signature-provider=EDT-KMS   # SunEC for software keys
edt.signing.certificate-store=...        # Docker: /certs/certificate-store.p12, mounted read-only
edt.policy.roles.other-user.srvs."4.1.1"=ESME,GSME    # PLACEHOLDER allowlist
```

Try it (with `dcc-tools` running, from the repository root, Git Bash):
```
docker compose up --build -d orchestrator duis-signer
# inputs: a JSON string with an unsigned DUIS Service Request (samples/sign-probe/), then the device type
MSYS_NO_PATHCONV=1 docker run --rm --network edt -v "$(pwd -W)/samples/sign-probe:/in:ro" temporalio/admin-tools:1.32.2 \
  temporal --address temporal:7233 --namespace edt \
  workflow execute --type SignProbe --task-queue orchestrator --workflow-id sign-probe-1 \
  --input-file /in/signed.json --input '"ESME"'
```
Result: `{"status":"SIGNED","userRole":"OTHER_USER","signedServiceRequest":"<sr:Request ...><Signature ...>"}`.
With `refused.json` and `--input '"CHF"'` (SRV 4.1.1 for a hub; it passes the schema, the policy refuses it):
`{"status":"REFUSED","refusalReason":"device type CHF is not allowed for SRV 4.1.1"}`.

Audit event (ECS JSON, shortened):
```
{"log.logger":"edt.audit","log.level":"INFO","message":"Service Request signed",
 "event.action":"sign-service-request","event.outcome":"success","edt.request_id":"REQ-0001",
 "edt.srv":"4.1.1","edt.user_role":"OTHER_USER","WorkflowId":"sign-probe-ok","ActivityType":"Sign",...}
```

Key ideas:
- **A refusal is a result, not an error** (the model: "returns the signed Service Request or the reason it was refused").
  Only technical errors (KMS down) throw, and Temporal retries them (max 3 attempts).
- **Contract by JSON field names**: `SignOutcome` exists in both applications with the same fields.
  Strings instead of enums, so a new value does not break the other side.
- **Temporal adds context to logs**: activity logs carry `WorkflowId`, `ActivityType`, `Attempt` automatically.
- **Quoted map keys**: an SRV like `4.1.1` has dots, so it is written `srvs."4.1.1"` in `application.properties`.
- Restarting `dcc-tools` creates new KMS keys and certificates: restart `duis-signer` afterwards.

### Step 12a: the Service Request workflow (tested in memory)

The heart of the model, in `orchestrator/.../adapter/ServiceRequestWorkflowImpl.java`. Not yet run in Docker:
the DUIS Sender has no implementation until the DSP simulator (12b).

```
ServiceRequestWorkflow (workflow ID = "service-request-" + Request ID)
  1. BuildServiceRequest   orchestrator queue   builds the unsigned XML, with the Request ID
  2. Sign                  duis-signing queue   DUIS Signer (refused -> stop, never sent)
  3. SendServiceRequest    duis-send queue      posts once, 1 attempt (the gateway retries)
  4. wait for signal "DspMessage" up to the response timeout (2 minutes locally)
       RESPONSE          -> RESPONDED
       DCC Alert N12/N13 -> FAILED_ALERT
       other DCC Alert   -> keep waiting
       nothing           -> TIMED_OUT
```

| File | What it is |
|---|---|
| `entity/RequestId` | Request ID = originator : target : counter, and the workflow ID rule. **PLACEHOLDER counter.** |
| `entity/DccAlerts` | Which alerts fail a Service Request (N12, N13). |
| `adapter/ServiceRequestWorkflow(Impl)` | The workflow and its signal `DspMessage`. |
| `adapter/RequestBuilderActivity(Impl)` | Builds the real DUIS 5.5 Service Request (see "Real DUIS Service Request" below). |
| `adapter/DuisSenderActivity`, `SyncResponse` | The sender's contract (implementation in 12b). |
| `adapter/ServiceRequestOutcome`, `DspMessage`, `ServiceRequestInput` | Data passed through Temporal as JSON. |

Where the Request ID comes from: the EDT creates it **before** sending (the Flow passes it in, the builder writes it
into the XML). The DSP echoes it back in the Response; the Response Listener reads it there and derives the workflow ID.

Tests (`ServiceRequestWorkflowTest`, in-memory Temporal, time skipped): response; **response arriving before the send
returns**; N12; another alert then a response; timeout; refused signature; rejection in the synchronous response;
failed post not retried.

Key ideas:
- **Signals**: a message to a running workflow, by workflow ID. Temporal stores it until the code reads it,
  so an early Response is never lost (the workflow exists before anything is sent).
- **Durable wait**: `Workflow.await(timeout, condition)` becomes a timer in Temporal: it survives restarts.
- **Deterministic time**: `Workflow.currentTimeMillis()` instead of the system clock.
- **Time skipping**: the test server jumps over timers, so a 2-minute timeout takes milliseconds.

To confirm with DCC / the lead:
- How the Request ID counter is generated (placeholder for now). Its text format, `originator:device:counter`, matches the released examples.
- Open point 2 (retries): built as the model says (send once, never resent). The call described resending.
- What DCC Alerts other than N12/N13 should do (for now: keep waiting).

#### Real DUIS Service Request

Since the change `real-duis-messages` (group 1) the Service Request is a real DUIS 5.5 request, in the shape of the
released Reference Test Data Set (RTDS 4.10.0, folder `4.1.1_ECS17b` and `8.9_CCS06`):

```xml
<sr:Request xmlns:sr="http://www.dccinterface.co.uk/ServiceUserGateway" schemaVersion="5.5">
  <sr:Header>
    <sr:RequestID>90-B3-D5-1F-30-01-00-01:00-DB-12-34-56-78-90-A0:1000</sr:RequestID>   <!-- originator:device:counter -->
    <sr:CommandVariant>1</sr:CommandVariant>                                            <!-- 1: a non-critical read -->
    <sr:ServiceReference>4.1</sr:ServiceReference>
    <sr:ServiceReferenceVariant>4.1.1</sr:ServiceReferenceVariant>                       <!-- the SRV -->
  </sr:Header>
  <sr:Body><sr:ReadInstantaneousImportRegisters/></sr:Body>
</sr:Request>
```

| Point | What it means |
|---|---|
| Five SRVs | The builder and the signer know five SRVs (table below). The placeholder Flow still sends **4.1.1** to meters and **8.9** to a Communications Hub; the draft Flows of `two-draft-flows` will send 8.9 and 6.30 (hub), 6.2.9 and 11.2 (meter). 8.9 replaced the placeholder 8.2, for which no Response is released. |
| No device type in the request | The signer's policy checks "device type allowed for the SRV" (model 100.010), so the Orchestrator gives the device type to the `Sign` activity as a second argument. The originator is the first part of the Request ID. |
| Partial schema | `duis-signer/src/main/resources/schema/service-request.xsd` is written from the examples and says so: it is **not** the DUIS XSD, which DCC has not provided. It accepts only the five request elements below, command variant 1, and an unsigned request. |
| Still placeholder | The synchronous response, the Response and the DCC Alerts the simulator writes (groups 3 to 5 replace the Response). The request Request ID and the counter (workflow time) are unchanged. |

The five SRVs (request element, signer allowlist of the Other User role; **PLACEHOLDER** allowlist until DCC provides the real one):

| SRV | Request | Service Reference | Body element | Allowed device types |
|---|---|---|---|---|
| 4.1.1 | Read Instantaneous Import Registers | 4.1 | `ReadInstantaneousImportRegisters` | ESME, GSME |
| 8.9 | Read Device Log | 8.9 | `ReadDeviceLog` | CHF |
| 6.2.9 | Read Device Configuration (Payment Mode) | 6.2 | `ReadDeviceConfigurationPaymentMode` | ESME, GSME |
| 6.30 | Read CHF Sub GHz Configuration | 6.30 | `ReadCHFSubGHzConfiguration` | CHF |
| 11.2 | Read Firmware Version | 11.2 | `ReadFirmwareVersion` | ESME, GSME, CHF |

Adding an SRV means a row in `RequestBuilderActivityImpl`, the schema, the allowlist (`SchemaAllowlistConsistencyTest` fails if the schema
has an SRV no allowlist has), and later its Response reader and the simulator's Response.

The files of the released set are in `samples/rtds_samples/` (not committed: licence to confirm). The tests hold short
messages written from them, and each says which example it comes from.

### Step 12b: the DSP simulator, and the real DUIS Sender

`dcc-tools/dsp-simulator` plays the DSP HTTPS Gateway, the DSP and the devices. Plain Java, no framework
(the JDK's `HttpServer` and `HttpClient`). The Orchestrator now runs the Service Request workflow for real.

```
orchestrator (worker on duis-send)  --POST signed Service Request-->  dsp-simulator :8090
                                    <--synchronous response (I0 accepted / E99 rejected)--
dsp-simulator  --later: POST Response or DCC Alert-->  response-listener :8084/duis/responses  (step 12c)
```

The **last group of the target device ID** chooses what the simulator does (PLACEHOLDER codes):

| Ends in | Synchronous | Then |
|---|---|---|
| `-A0` (default) | accepted | Response after 5 s |
| `-A1` / `-A2` | accepted | DCC Alert N12 / N13 |
| `-A3` | accepted | nothing (the EDT times out) |
| `-A4` | rejected | nothing |
| `-A5` | accepted | DCC Alert N53, then Response |

Delivery to the Response Listener is retried 5 times, 3 s apart (the gateway retries; the listener answers 500
when Temporal is down), then dropped.

| File | What it does |
|---|---|
| `dcc-tools/dsp-simulator/.../DspSimulator.java` | HTTP server: answers at once, schedules the asynchronous messages. |
| `.../DeviceBehaviour.java` | Behaviour from the device ID. |
| `.../DspMessages.java` | Reads real DUIS 5.5 Service Requests (the device is the middle part of the Request ID); writes the real Response (MMC 5.5: electricity or gas for 4.1.1, device log for 8.9, payment mode for 6.2.9, Sub GHz configuration for 6.30, firmware version for 11.2; gas for the devices in `EDT_GAS_DEVICES`; 8.9 and 6.30 come through the Access Control Broker with the supplementary fields) and the PLACEHOLDER sync response and DCC Alert. |
| `orchestrator/.../adapter/DuisSenderActivityImpl.java` | Posts once with the JDK `HttpClient`, reads the sync response. HTTP error -> exception. |
| `orchestrator/.../infrastructure/TemporalWorker.java` | Two workers: `orchestrator` (workflows, builder) and `duis-send` (sender). |

Run a Service Request (both stacks running; inputs in `samples/service-request/`):
```
cd dcc-tools && ./gradlew installDist && docker compose up -d --build && cd ..
docker compose up --build -d orchestrator duis-signer
MSYS_NO_PATHCONV=1 docker run --rm --network edt -v "$(pwd -W)/samples/service-request:/in:ro" temporalio/admin-tools:1.32.2 \
  temporal --address temporal:7233 --namespace edt workflow execute --type ServiceRequest --task-queue orchestrator \
  --workflow-id "service-request-90-B3-D5-1F-30-01-00-01:00-DB-12-34-56-78-90-A4:5" --input-file /in/A4-rejected.json
```
The workflow ID is `service-request-` + the `requestId` in the file.

Until step 12c, accepted requests end `TIMED_OUT` after 2 minutes: the simulator cannot deliver the Response
(no Response Listener yet). In the Temporal UI the wait is the event `TimerStarted`, then `TimerFired`.

Key ideas:
- **Two task queues, one application**: one worker per queue. The send queue gets its rate limit in 12d.
- **Synchronous vs asynchronous**: the HTTP reply only says "accepted"; the device's answer comes later, on another
  connection, to another application.
- Not yet: the DSP signature on its messages, and its check by the sender and the Response Listener.

### Step 12c: the Response Listener signals the waiting workflow

The asynchronous answer now reaches the workflow. The Response Listener runs **no worker**: it is only a
Temporal *client* that sends signals.

```
dsp-simulator --POST Response / DCC Alert--> response-listener :8084/duis/responses
   read RequestId (and AlertCode) -> workflow ID "service-request-" + RequestId
   --signal "DspMessage" {type, alertCode, xml}--> Temporal Server --> Service Request workflow (in the orchestrator)
```

No database: the Request ID alone gives the workflow ID, because both applications apply the same rule
(each its own copy). The signal name and its JSON fields are the whole contract between them.

The HTTP status tells the gateway whether to retry:

| Case | Status |
|---|---|
| delivered to the waiting workflow | 200 `signalled` |
| no running workflow (ended, for example timed out): logged as `dsp-message-orphan` | 200 `orphan` (a retry would not help) |
| not a readable Response or DCC Alert | 400 |
| Temporal unreachable | 500 (the gateway retries; the model says 500) |

| File | What it does |
|---|---|
| `response-listener/.../entity/ServiceRequestWorkflowId.java` | The rule Request ID -> workflow ID. |
| `.../usecase/RouteDspMessage.java` | read -> (DSP signature check, later) -> workflow ID -> signal. |
| `.../adapter/XmlDspMessageReader.java` | Reads the real `GBCSResponse` (rebuilds the Request ID from its header; through the hub from the supplementary fields) and the `DCCAlert` (PLACEHOLDER XML); refuses a device alert, a placeholder `Response` and DTDs. |
| `.../adapter/TemporalWorkflowSignaller.java` | `client.newUntypedWorkflowStub(id).signal("DspMessage", payload)`. `WorkflowNotFoundException` = orphan. |
| `.../adapter/DspMessageResource.java` | `POST /duis/responses`; maps the result to the HTTP status; logs. |

Test `TemporalWorkflowSignallerTest` runs a stand-in workflow with the same type, signal name and fields as the
Orchestrator's, on Temporal's in-memory server: a check of our side of the contract.

Run: `docker compose up --build -d response-listener`, then the samples as in 12b. Results now:
A0 and A5 `RESPONDED`, A1 and A2 `FAILED_ALERT`, A3 `TIMED_OUT`, A4 `REJECTED_BY_DSP`.
In the Temporal UI the wait now ends with `WorkflowExecutionSignaled` instead of `TimerFired`.

Key ideas:
- **Signal through the server**: the listener never calls the Orchestrator. If the Orchestrator is down, the signal
  waits in the workflow history and is processed when it comes back.
- **A signal for an ended workflow fails**: that is how orphans are detected, with no table of our own.
- Not yet: the DSP signature check (needs a DSP key and certificate in dcc-tools).

### Step 12d: rate limit and fairness on the send queue

Every Service Request of every Batch leaves the EDT through one task queue, `duis-send`. The model asks for two things:
- a **global rate limit**: at most N per second to the DSP, across all workflows and all replicas;
- **fairness by Batch**: a large Batch does not hold up a small one.

Both are done by the Temporal Server, not by our code:

| What | Where | How |
|---|---|---|
| Send rate limit (1/s locally) | `orchestrator/.../infrastructure/TemporalWorker.java` | `WorkerOptions.setMaxTaskQueueActivitiesPerSecond(...)` on the `duis-send` worker |
| Signing rate limit (10/s locally, KMS quota) | `duis-signer/.../infrastructure/TemporalWorker.java` | the same, on the `duis-signing` worker |
| Fairness key = Batch ID | `orchestrator/.../adapter/ServiceRequestWorkflowImpl.java` | `ActivityOptions.setPriority(Priority.newBuilder().setFairnessKey(batchId))` on the sender stub |
| Fairness switched on | `dcc-tools/temporal/dynamicconfig/development-sql.yaml` | `matching.enableFairness: true` (off by default when self-hosted) |

Config: `edt.service-request.send-rate-per-second=1`, `edt.temporal.signing-rate-per-second=10`. Both **[CONFIRM]** with DCC.
`ServiceRequestInput` now has a `batchId` (the Batch workflow will pass it; for now the samples do).

- `setMaxTaskQueueActivitiesPerSecond` limits the **whole queue**, and the server enforces it. Two replicas at 1/s
  still send 1/s in total. (`setMaxWorkerActivitiesPerSecond` would limit each worker alone, so more replicas would mean
  more requests.) Every replica must use the same value.
- With a fairness key, the server keeps one virtual queue per Batch and takes from them in turn.
- The rate limit says *how many* per second; fairness says *whose turn* it is.

Try it: `bash samples/rate-limit/run.sh` starts 40 requests of Batch `big`, then 2 of Batch `small`, and prints
the order in which the DSP simulator received them. Measured:

| | `small` sent as | waited |
|---|---|---|
| fairness on | 13th and 15th (about 28 `big` still waiting) | ~1 s |
| fairness off | 41st and 42nd (behind all of `big`) | ~30 s |

What I learned while testing:
- **Partitions.** The server splits each task queue into 4 partitions by default and shares the rate between them.
  With a small load the backlog sits unevenly, so the real rate fell to 0.25/s with a 1/s limit. Locally `duis-send`
  has 1 partition (dynamic config), and the rate is exactly 1/s. In production DCC sizes partitions for the load. **[CONFIRM]**
- **Do not toggle fairness on a busy queue.** Switching it on and off without a restart gave mixed results.
  Temporal documents a migration setting (`matching.enableMigration`) for switching a live queue.
- The test `the_send_is_keyed_by_batch_for_fairness` checks only that the key is set. The in-memory test server does
  not enforce rate limits or fairness, so the behaviour is shown with the real server.

### Step 12e: two fixes after checking the model (views 010.030 and 100.010)

1. **Response Listener answers 500 when Temporal is down** (it answered 503). The model's Response Endpoint says
   500, so the message is delivered again. `response-listener/.../adapter/DspMessageResource.java`.
2. **Temporal SDK metrics at `/q/metrics`** (the model: "Prometheus metrics (JVM and Temporal SDK)").
   In each app's `infrastructure/TemporalConnection.java`:

```
Temporal SDK --metrics (Tally)--> MicrometerClientStatsReporter --> Quarkus MeterRegistry --> /q/metrics
```
   A `Scope` is given to `WorkflowServiceStubsOptions.setMetricsScope(...)`; workers built on that client report into it.
   No new dependency: Tally comes with the Temporal SDK. Useful series, for example:
   - `temporal_activity_schedule_to_start_latency_seconds{activity_type="SendServiceRequest"}`: how long sends wait
     in the rate-limited queue (a growing value means the send rate is the bottleneck);
   - `temporal_activity_execution_latency_seconds{activity_type="Sign"}`: signing time, KMS included;
   - `temporal_request_total{operation="SignalWorkflowExecution"}`: signals sent by the Response Listener.

Still open from that check: the DSP signature check (done in step 13), and the Response Decoder with its Facts
(steps 20 to 22).

### Step 13: the DSP signature check (model 010.030, steps 13 and 18)

The EDT trusts a message from the DSP only after checking its XML signature with the **trusted DSP certificate**
from the certificate store. Two places check it, as the model says:

| Where | What | If the signature is bad |
|---|---|---|
| `orchestrator/.../adapter/DuisSenderActivityImpl.java` | the synchronous DUIS response | security event; non-retryable failure `DspSignatureInvalid`; workflow `SEND_FAILED` (never sent again) |
| `response-listener/.../usecase/RouteDspMessage.java` | each Response and DCC Alert, **before** reading anything in it | security event; discarded; HTTP 400; nothing signalled |

```
kms-setup --DSP private key--> dcc-tools/dsp-keys/dsp-signing.p12  (mounted ONLY into dsp-simulator)
          --DSP certificate--> dcc-tools/certs/certificate-store.p12, alias "dsp"  (mounted read-only into the EDT apps)
dsp-simulator signs every message --> orchestrator / response-listener check it with that certificate
```

| File | What it does |
|---|---|
| `.../adapter/DspSignatureVerifier.java` (own copy in each app) | One `ds:Signature`, child of the root; one Reference with URI `""` and the enveloped transform (it covers the whole message); ECDSA-SHA256; valid for the **trusted** key. The certificate inside the message is ignored. JDK secure validation on. |
| `.../infrastructure/DspCertificateWiring.java` | Loads the certificate (JCA `KeyStore` PKCS12) at startup (`@Startup`); missing = the app does not start. |
| `response-listener/.../usecase/DspSignatureCheck.java` | The port: the use case checks first, then reads. |
| `dcc-tools/dsp-simulator/.../DspSigner.java` | The simulated DSP signs like the EDT signer (enveloped, ECDSA-SHA256, exclusive C14N). |
| `dcc-tools/kms-setup/.../KmsSetup.java` | Makes the DSP key pair (software: the DSP is not in KMS) and its certificate from the test CA. |

New simulator behaviours: `-A6` a Response changed after signing (I0 becomes I99), `-A7` a synchronous response
changed after signing. Results: A6 `TIMED_OUT` (the Response was discarded), A7 `SEND_FAILED`.
The simulator no longer retries a 4xx answer (refused), only connection errors and 5xx.

Security events go to the logger `edt.security` with `event.kind=alert`, `event.category=intrusion_detection`,
`event.action=dsp-signature-invalid`. The listener logs nothing from the message itself, because it is not trusted.

Key ideas:
- **Pin the certificate**: verify with the key you trust, not the one the message brings.
- **Check before reading**: in the listener even the Request ID is untrusted until the signature is checked.
- **Keep private keys apart**: the DSP key has its own folder, so no EDT container can see it.
- To confirm with DCC: the real DSP signature format (DUIS) and how the DSP certificate is renewed. **[CONFIRM]**

### Step 14: the Response Decoder and the Facts (model 010.030, steps 20 to 22)

When the Response arrives, the Service Request workflow has it decoded and returns **the outcome and the Facts**
to its caller (the Flow, later). Facts are the values from the Response that the Flow decides on.

```
Service Request workflow --DecodeResponse(srv, Response)--> ResponseDecoderActivity --> ResponseDecoder port
                         <--Facts {MessageSuccess=true, ActiveImportRegister=10, ...}--
outcome: {"status":"RESPONDED","facts":{"values":{"ActiveImportRegister":"10","ActiveImportRegister.Unit":"Wh",...,"MessageSuccess":"true"}}, ...}
```

| File | What it does |
|---|---|
| `orchestrator/.../entity/Facts.java` | Name -> value, sorted, immutable. |
| `.../usecase/ResponseDecoder.java` | The port. In the model this is **Parsec** (CSW software, GBCS decoding), which we do not have. |
| `.../adapter/RealResponseDecoder.java` | Reads the real decoded Response (MMC XML, what Parsec yields) of SRV 4.1.1, 8.9, 6.2.9, 6.30 and 11.2 into Facts (table below). Written from the released RTDS 4.10.0 examples. A failed read of the last three names its result in `DataAccessResult`, which goes into `DebugSummary`. |
| `.../adapter/ResponseDecoderActivityImpl.java` | Activity `DecodeResponse`; logs `response-decoded` (the model's "each Response decoded"); a payload it cannot decode fails with non-retryable `ResponseNotDecodable`. |
| `.../adapter/ServiceRequestWorkflowImpl.java` | After the Response: decode, then return `RESPONDED` with the Facts, or `DECODE_FAILED`. |

Changes:
- `ServiceRequestOutcome` returns `facts` instead of the Response XML: the model returns "outcome and Facts", and
  Temporal histories stay smaller.
- New status `DECODE_FAILED`: the device answered, but the Response could not be decoded. It is not sent again.
- Failure details now show the original message (for example A7: "DSP signature on the synchronous response is not
  valid: signature does not match"), without Temporal's `message='...', type='...'` wrapper.

The Facts (flat `name -> text`):

| SRV, content | Facts |
|---|---|
| 4.1.1, electricity meter | `ActiveImportRegister`, `ReactiveImportRegister` (and `SecondaryActiveImportRegister` on a twin-element meter), each with `<name>.Unit` |
| 4.1.1, gas meter | `ConsumptionRegister` and `ConsumptionRegister.Unit` |
| 8.9, hub | `CHFDeviceLog.<deviceId>.LastCommunicationsDateTime` and `.SubGHzLinkQuality` for each device in the log |
| 6.2.9, meter | `PaymentMode` |
| 11.2, any device | `FirmwareVersion` |
| 6.30, hub | every value that is plain text in the Response, named after its element (`MaximumSubGHzChannelChangesPerWeek`, `GSMECurfew`, the duty cycle and noise thresholds...). The channel lists are not Facts |
| any | `MessageSuccess`; when it is `false` also `DebugSummary` (the distinct non-success COSEM and ZigBee result strings, comma-separated). A value the Response lacks gives no Fact. |

`MessageSuccess=false` is recorded, not judged: the Service Request stays `RESPONDED` (design D5). The Response is the **decoded MMC XML**, confirmed as what the DSP delivers.
The DSP simulator answers 4.1.1 with an electricity reading, or a gas reading for the device IDs in `EDT_GAS_DEVICES` (a comma-separated list; Compose and the dcc-tools chart set `00-DB-12-34-56-78-93-A0`, the GSME of `samples/manifests/device-types.csv`).

To confirm with DCC / the lead: which Facts each SRV yields (depends on the final Flows) and how Parsec is called. **[CONFIRM]**

### Step 15: a signed Manifest in S3 and its message on SQS (dcc-tools, for Batch Ingest)

dcc-tools makes only what Batch Ingest will read (view 100.010). It does not copy DCC's plumbing: there is no
EventBridge rule and no dead-letter queue.

```
manifest-upload device-behaviours.csv
  1. SHA-256 of the file, streamed           (MessageDigest, constant memory)
  2. KMS Sign(digest, DIGEST, ECDSA_SHA_256)  with alias/dcc-manifest  (DCC's key, never leaves KMS)
  3. S3 PutObject edt-manifests/device-behaviours.csv, tag dcc-signature=<base64 DER>  -> new object version
  4. SQS SendMessage edt-manifest-events: {"detail-type":"Object Created", "detail":{bucket, object{key, size, version-id}}}
```

| File | What |
|---|---|
| `dcc-tools/kms-setup/.../KmsSetup.java` | Also creates the Manifest key `alias/dcc-manifest`, the versioned bucket `edt-manifests` and the queue `edt-manifest-events`. |
| `dcc-tools/manifest-upload/.../ManifestUpload.java` | Steps 1 to 4. `--bad-signature` signs a different digest, for the negative test. |
| `samples/manifests/` | PLACEHOLDER columns `device_id,device_type,flow`, one row per device. Three files, each with its own purpose: `one-device.csv`, `device-types.csv`, `device-behaviours.csv` (see its README). |

Try it (from `dcc-tools/`, Git Bash):
```bash
./gradlew installDist && docker compose up -d --build
MSYS_NO_PATHCONV=1 docker compose run --rm manifest-upload /manifests/device-behaviours.csv
MSYS_NO_PATHCONV=1 docker compose run --rm manifest-upload /manifests/device-behaviours.csv --bad-signature
```
Each upload is a new object version, as the model says ("resubmitting a file starts a new Batch").

Verified with the AWS CLI, doing what Batch Ingest will do (get the version, read its tag, KMS Verify the digest):
the valid version gives `SignatureValid: True`. The bad one gives `KMSInvalidSignatureException`: KMS reports a wrong
signature as an **error**, not as `false`, so Batch Ingest must treat that exception as "invalid Manifest".

Note: running `kms-setup` again makes a new DSP key. Restart `dsp-simulator`, `orchestrator`, `response-listener` and
`duis-signer` afterwards.

To confirm with DCC: the Manifest columns, and the signature's tag name and format. **[CONFIRM]**

### Step 16: Batch Ingest (model 100.010)

```
SQS long poll (20 s) --"Object Created"--> Manifest Event Consumer
   |  parse: bucket, key, version-id
   v
IngestManifest (use case)
   1. Manifest Signature Verifier: S3 GetObjectTagging(version) -> dcc-signature
                                   S3 GetObject(version), streamed into Signature("SHA256withECDSA", "EDT-KMS")
                                   verify -> KMS Verify(digest, DIGEST, alias/dcc-manifest)
   2. invalid -> Rejected      valid -> Batch Starter: start workflow "Batch" on queue "orchestrator",
                                        ID batch-<bucket>/<key>/<version>, REJECT_DUPLICATE
   v
Started / AlreadyStarted / Rejected -> delete the message
S3, KMS or Temporal unreachable, unreadable message -> leave it: SQS gives it again (DCC's dead-letter queue in production)
```

| File (under `batch-ingest/src/main/java/prepare/edt/batchingest/`) | What |
|---|---|
| `entity/ManifestRef.java` | Bucket, key, version; `batchWorkflowId()`. |
| `usecase/IngestManifest.java` | Verify first, then start; outcome Started, AlreadyStarted or Rejected. Ports `ManifestVerifier`, `BatchStarter`. |
| `adapter/SqsManifestEventConsumer.java` | Long poll, logs `manifest-received` and `sqs-message-deleted` on `edt.interaction`, deletes only on an outcome. A rejection is a security event on `edt.security` (`manifest-signature-invalid`). |
| `adapter/S3ManifestVerifier.java` | Tag + streamed object into a JCA `Signature`. Missing tag, not base64, malformed or wrong signature: invalid. |
| `adapter/kms/` | Batch Ingest's own `EDT-KMS` provider: `SHA256withECDSA` whose verify calls **KMS Verify**. `KMSInvalidSignatureException` means `false`. Any other KMS error is a `ProviderException`: not a verdict, the message comes back. It refuses to sign. |
| `adapter/TemporalBatchStarter.java` | Untyped stub, type `Batch`, input `BatchInput(bucket, key, versionId)`. `WorkflowExecutionAlreadyStarted` means already started. |
| `infrastructure/ManifestEventPolling.java` | One polling thread for the life of the app; after an error it waits 5 s. |

Exactly once per upload:
- The workflow ID comes from the object version, so the same message twice starts one Batch.
- REJECT_DUPLICATE: also after that Batch has ended.
- A new upload of the same file is a new version, so a new Batch (the model's "resubmitting a file starts a new Batch").

Verified (dcc-tools running, `docker compose up -d --build batch-ingest`):

| Message | Result |
|---|---|
| valid Manifest | `Batch started`, workflow `batch-edt-manifests/<file>/<version>` Running in Temporal |
| `--bad-signature` | `Manifest rejected: its DCC signature is not valid` on `edt.security`, no workflow |
| same message again | `Batch already started: duplicate message`, deleted |

The Orchestrator runs the `Batch` workflow since step 17.

### Step 17: the Batch and Page workflows (model 010.010, step 11)

```
Batch  (ID batch-<bucket>/<key>/<version>, from Batch Ingest)
  start Page n at the known offset, at most pages-at-a-time running
     Page n: ReadManifestPage activity  (S3 GetObject Range: bytes=a-b, chunk by chunk, until rows-per-page rows)
             --signal "PageRead"(n, next offset, end?)--> Batch: Page n+1 can start
             one Flow per row, at most flows-per-page at a time  (IDs <batch>/row-<number>)
             returns devices, invalid rows, Verdict counts
  every pages-per-run Pages: wait for them, continue as new carrying (next Page, offset, counts)
  end: Batch result  (logged; published to Kafka in a later step)
Flow (PLACEHOLDER): one ServiceRequest child (8.9 hubs, 4.1.1 meters) -> RESPONDS / NO_RESPONSE / NOT_DIAGNOSED
```

| File (under `orchestrator/src/main/java/prepare/edt/orchestrator/`) | What |
|---|---|
| `entity/ManifestRow.java` | PLACEHOLDER CSV row `device_id,device_type,flow`, validated; invalid reasons carry no content. |
| `usecase/ReadManifestPage.java` | Byte ranges into whole rows: a chunk may end mid-line; returns the next Page's offset. Header at offset 0; CRLF, BOM, blank lines handled. |
| `adapter/S3ManifestBytes.java` | `GetObject` of the version with a `Range` header; 416 means past the end. |
| `adapter/ManifestReaderActivityImpl.java` | Logs each invalid row (`manifest-row-invalid`, row number) and each Page read. Not a Manifest: non-retryable `NotAManifest`. |
| `adapter/BatchWorkflowImpl.java` | Pages, the `PageRead` signal, continue-as-new, query `progress` (Temporal UI, Queries tab). |
| `adapter/PageWorkflowImpl.java` | Reads, signals the Batch, runs the Flows with a limit. |
| `adapter/PlaceholderFlowWorkflowImpl.java` | PLACEHOLDER until the Flow Catalogue (010.020). |
| `entity/FairnessKey.java` | Fix found live: Temporal refuses a fairness key over **64 bytes**. A longer Batch ID becomes its SHA-256 (base64url, 43 characters). |

Settings (`edt.batch.*`, small locally so the 5-row sample shows everything): `rows-per-page=2`, `pages-at-a-time=2`,
`flows-per-page=2`, `pages-per-run=2`, `read-chunk-bytes=65536`. **[CONFIRM]** production values.

**[INFERRED]** The model says the Page reads its rows, but the Batch needs the next Page's offset to keep several Pages
running. So the Page signals it back (`PageRead`) as soon as it has read. The model does not draw this.

Verified with `samples/manifests/device-behaviours.csv` (upload as in step 15; one ESME per simulator behaviour, A0 to A7):

| Device | Service Request | Verdict |
|---|---|---|
| A0, A5 | RESPONDED | RESPONDS |
| A1, A2 | FAILED_ALERT (N12, N13) | NO_RESPONSE |
| A3, A6 | TIMED_OUT (A3 silent; A6's tampered Response was discarded) | NO_RESPONSE |
| A4 | REJECTED_BY_DSP | NOT_DIAGNOSED |
| A7 | SEND_FAILED (tampered synchronous response refused) | NOT_DIAGNOSED |

Result: `{"devices":8,"invalidRows":0,"pages":5,"verdicts":{"NOT_DIAGNOSED":2,"NO_RESPONSE":4,"RESPONDS":2}}`.
The Batch continues as new after enough Pages. `device-types.csv` gives the three Response shapes and their Facts (step 14).

### Step 18: Kafka (bare minimum) and the Result Publisher

**dcc-tools: one broker** (`apache/kafka:4.1.0`, KRaft: no ZooKeeper, plain text, in memory). Settings in
`dcc-tools/docker-compose.yml`:

| Setting | Why |
|---|---|
| `KAFKA_PROCESS_ROLES=broker,controller` | One node is both the broker (stores messages) and the KRaft controller (cluster metadata). |
| `KAFKA_LISTENERS` / `KAFKA_ADVERTISED_LISTENERS` | `INTERNAL kafka:9092` for containers, `EXTERNAL localhost:29092` for quarkus dev. A client connects to any bootstrap address, then uses the *advertised* one. |
| `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false` | Topics exist only as created below, so their config is explicit. |
| `*_REPLICATION_FACTOR=1` | Kafka's internal topics: one copy, one broker. |

**Topics** (`dcc-tools/kafka/create-topics.sh`, one-shot `kafka-topics` service). Names are PLACEHOLDERS: DCC owns them. **[CONFIRM]**

| Topic | Key | Event ID (header `event-id`) |
|---|---|---|
| `edt.flow-steps` | device ID | `<flow workflow ID>/step-<n>` |
| `edt.device-results` | device ID | `<flow workflow ID>` (`<batch>/row-<n>`) |
| `edt.batch-results` | Batch ID **[INFERRED]**: a Batch has no device | `<batch workflow ID>` |

Each topic has `partitions=3`, `replication-factor=1` (production 3), `retention.ms=7 days`, `cleanup.policy=delete`
and `min.insync.replicas=1` (production 2).

The **key** decides the partition (hash of the key), so all events of one device are in one partition, in order.
There is no order *across* partitions. In the run below, 4 of the 5 devices hashed to partition 1 and A5 to partition 0.

**Orchestrator: the producer** (`infrastructure/KafkaWiring.java`, plain `kafka-clients`, no Quarkus messaging layer):

| Producer setting | Why |
|---|---|
| `acks=all` | The broker answers once every in-sync copy has the message. |
| `enable.idempotence=true` | The client's own resends never duplicate a message. |
| `delivery.timeout.ms=20000` | Give up inside the activity's 30 s; then Temporal retries the activity. |
| String key and value serializers | Key = device ID, value = JSON. Headers `event-id`, `event-type`. |

| File | What |
|---|---|
| `usecase/EventPublisher.java` | Port: publish (topic, key, event ID, type, JSON). |
| `adapter/KafkaEventPublisher.java` | `ProducerRecord` + headers; waits for the broker's answer (`send().get()`). |
| `adapter/ResultPublisherActivity(Impl).java` | Model's Result Publisher Activity: `PublishFlowStep`, `PublishDeviceResult`, `PublishBatchResult`. |
| Placeholder Flow / Batch | Publish the step and the device result / the Batch result. |

A retried activity can send the same event twice: idempotence covers only the client's own resends. Consumers
drop duplicates by `event-id` (model).

Read a topic (partition, key, headers, value):
```bash
MSYS_NO_PATHCONV=1 docker run --rm --network edt apache/kafka:4.1.0 /opt/kafka/bin/kafka-console-consumer.sh   --bootstrap-server kafka:9092 --topic edt.device-results --from-beginning --timeout-ms 8000   --property print.partition=true --property print.key=true --property print.headers=true
```

Verified with the sample Manifest: 11 messages (5 steps, 5 device results, 1 Batch result). A device result:
```
Partition:1  event-id:batch-.../row-1,event-type:DeviceResult  00-DB-12-34-56-78-90-A0
{"eventId":"batch-.../row-1","time":"...","batchId":"batch-...","deviceId":"00-DB-12-34-56-78-90-A0","rowNumber":1,"flow":"flow-1","verdict":"RESPONDS"}
```

Seen once in that run: A3's 2-minute timer fired after 5 min 50 s (the history shows `startToFireTimeout: 120s`).
Run alone, A3 took 2m0.3s, so it was the local environment (Docker VM or machine paused?), not the code. Watch for it.

### Step 19: Run on a local cluster (Kubernetes and Helm)

The same four applications and the same `dcc-tools`, on the Kubernetes that comes with Docker Desktop, installed with
Helm. The OpenSpec change `local-kubernetes-helm` (in `openspec/changes/`) has the reasons and the decisions.
Docker Compose stays as it is; use one or the other, because both want the ports 8081-8084, 8088, 8089 and 4566.

**Prerequisites** (nothing needs an account):
- Docker Desktop with **Kubernetes enabled** (Settings, Kubernetes). `kubectl config current-context` must say `docker-desktop`.
- `kubectl` and `helm` on your `PATH`.
- JDK 25 (the Gradle wrapper does the rest): the script builds the applications and runs `kms-setup` on your machine.
- On Windows, run the script from Git Bash.

```
deploy/edt.sh up        # build, install, wait until the four apps are ready (about 3 minutes the first time)
deploy/edt.sh forward   # Temporal UI :8088, Kafka UI :8089, Floci :4566, the apps' health on :8081-:8084; Ctrl+C stops
deploy/edt.sh down      # remove everything the script created
```

| Command | What it does |
|---|---|
| `up` | Builds the apps (tests skipped) and the five images, loads them into the cluster node, installs the `dcc-tools` chart, creates the keys and Secrets, installs the `edt` chart, then waits up to 300 s (`EDT_UP_TIMEOUT`). If an app is not ready in time it names it, shows the pods and the last log lines, and exits with an error. |
| `secrets` | Only the keys step: creates the KMS keys, the bucket and the queue in Floci, then the Secrets `edt-certificate-store` (namespace `edt`) and `dsp-signing-key` (namespace `dcc-tools`). Part of `up`. |
| `forward` | Port-forwards to the same local ports as Compose. |
| `down` | Uninstalls both charts and deletes the namespaces `edt` and `dcc-tools`. Nothing else in the cluster is touched. |

Where things are: the apps in namespace `edt`, the emulated DCC side in `dcc-tools`.
```
kubectl -n edt get pods
kubectl -n edt logs deploy/orchestrator -f
kubectl -n dcc-tools get pods
```
All the files are in `deploy/`: `edt.sh`, `check-env-keys.sh`, and the charts `helm/edt` and `helm/dcc-tools` (each has a
README with every value).

**After a Floci restart.** Floci keeps everything in memory, so a restart loses the keys, the bucket and the queue, and
the Secrets no longer match. Run `deploy/edt.sh up` again: it creates the keys and the Secrets again and restarts the
apps and the simulator, which read them only at start. (`deploy/edt.sh secrets` alone replaces the Secrets but does not
restart anything.)

**What the cluster does when something is wrong** (checked with Temporal scaled to zero):
- The apps stop being ready within about 30 seconds. Running pods are not killed: they are just not ready.
- A pod that starts while Temporal is down: the Orchestrator and the DUIS Signer exit at once and the cluster restarts
  them (`CrashLoopBackOff`); Batch Ingest and the Response Listener stay running and not ready.
- When Temporal is back, everything becomes ready again without a command.

Key ideas:
- **Same images, same settings.** The environment of each app is the one in `docker-compose.yml`, with the short service
  names replaced by full cluster names (`temporal.dcc-tools.svc.cluster.local`). `deploy/check-env-keys.sh` fails if the
  two ever differ.
- **Images are loaded into the node.** The cluster node does not see images built with Docker, so `up` imports each one
  (`docker save | ctr images import`). A rebuilt image must be loaded again, which `up` does.
- **Keys are made on your machine.** `kms-setup` runs on the host against Floci through a port-forward, writes to a
  temporary folder, and only the Secrets stay. Nothing key-related is written to the repository.
- **Start order by retrying.** There is no `depends_on`. Jobs and pods retry until what they need is there, so you see a
  few restarts at the start. That is normal.
- **Only two namespaces.** `edt` for what we build, `dcc-tools` for what DCC provides in production.
- **Not the production shape.** One pod per app, no autoscaling, no NetworkPolicy: `deploy/helm/edt/README.md` lists each
  difference from the architecture model.

## Service interactions

From the architecture model. Implemented so far: `batch-ingest` (SQS, S3, KMS Verify, starts the Batch), `orchestrator` to `duis-signer` (Sign activity, signing in KMS), `orchestrator` to the DSP (simulator),
and the DSP back to `response-listener`, which signals the workflow.

The applications never call each other directly. They talk through the Temporal Server:

```
S3 upload -> SQS -> batch-ingest --start Batch workflow--> Temporal
                                                              |
orchestrator <-- runs Batch, Page, Flow, Service Request workflows
     |  --Sign activity (signing task queue)--> duis-signer --> KMS
     |  --signed Service Request--> DSP Gateway --> DSP --> device
     |
     |  (minutes or hours later)
     |  DSP --> DSP Gateway --> response-listener --signal (workflow ID from Request ID)--> Temporal
     |
     +--> Kafka: step events, device results, Batch result
```

| From | To | How |
|---|---|---|
| `batch-ingest` | SQS, S3, KMS, Temporal | receives the Object Created message, checks the Manifest signature (KMS Verify), starts the Batch workflow (ID from bucket, key, object version) **(working: Floci; the Batch workflow itself is next)** |
| `orchestrator` | `duis-signer` | Temporal activity on the signing task queue **(working: signs in KMS)** |
| `orchestrator` | DSP Gateway | HTTP POST of the signed Service Request, once, rate-limited **(working: DSP simulator; 1/s, fair by Batch)** |
| `response-listener` | Temporal | signals the Service Request workflow (ID from the Request ID) **(working, after checking the DSP signature)** |
| `orchestrator` | S3 | byte-range reads of the Manifest rows **(working: Floci)** |
| `orchestrator` | Kafka | publishes step, device and Batch results **(working: one broker in dcc-tools)** |
