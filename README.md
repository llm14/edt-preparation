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
{"@timestamp":"...","log.level":"INFO","message":"DUIS Signer started","mdc":{"event.action":"startup"},"service.name":"duis-signer"}
```

Output in dev:
```
16:02:30 INFO  [pr.ed.du.StartupLog] DUIS Signer started {event.action=startup}
```

Key ideas:
- **MDC** (Mapped Diagnostic Context): fields attached to the current thread, added to every log line until removed.
  `LogFields` removes them when the `try` block ends.
- Custom fields appear under `mdc`, not at the top level.
- Temporal workflow code needs its own logger (`Workflow.getLogger`), because workflows are replayed. Comes later.

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
| `<app>/Dockerfile` | Java 25 runtime (`eclipse-temurin:25-jre`) plus `build/quarkus-app`. Runs as user 1001, not root. |
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
  workflow execute --type SignProbe --task-queue orchestrator --workflow-id sign-probe-1 --input '"<request/>"'
```
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
| `XmlServiceRequestReader` | Parses safely (no DTD, so no XXE), validates against `schema/service-request-placeholder.xsd` (**PLACEHOLDER**, not DUIS), reads RequestId, SRV, Originator, DeviceType. |
| `JcaXmlSigner` | Adds an enveloped XML signature: ECDSA P-256 + SHA-256, with the role's certificate in `KeyInfo`. Keys from a JCA `KeyStore`, signature from a JCA provider chosen by name. |
| `SecureXml` | Safe parse and exact write of XML (shared by both). |

Signed result (shortened):
```xml
<ServiceRequest xmlns="urn:edt:placeholder:duis">
  <Header>...</Header><Body>...</Body>
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
</ServiceRequest>
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
# input: a JSON string with an unsigned placeholder Service Request (samples/sign-probe/)
MSYS_NO_PATHCONV=1 docker run --rm --network edt -v "$(pwd -W)/samples/sign-probe:/in:ro" temporalio/admin-tools:1.32.2 \
  temporal --address temporal:7233 --namespace edt \
  workflow execute --type SignProbe --task-queue orchestrator --workflow-id sign-probe-1 --input-file /in/signed.json
```
Result: `{"status":"SIGNED","userRole":"OTHER_USER","signedServiceRequest":"<ServiceRequest ...><Signature ...>"}`.
With `refused.json` (SRV `9.9.9`): `{"status":"REFUSED","refusalReason":"SRV 9.9.9 is not on the allowlist of any User Role"}`.

Audit event (ECS JSON, shortened):
```
{"log.logger":"edt.audit","log.level":"INFO","message":"Service Request signed",
 "mdc":{"event.action":"sign-service-request","event.outcome":"success","edt.request_id":"REQ-0001",
        "edt.srv":"4.1.1","edt.user_role":"OTHER_USER","WorkflowId":"sign-probe-ok","ActivityType":"Sign",...}}
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
| `adapter/RequestBuilderActivity(Impl)` | Builds the PLACEHOLDER XML the signer validates. |
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
- How the Request ID counter is generated (placeholder for now) and its exact format.
- Open point 2 (retries): built as the model says (send once, never resent). The call described resending.
- What DCC Alerts other than N12/N13 should do (for now: keep waiting).

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
| `.../DspMessages.java` | Reads Service Requests, writes the PLACEHOLDER sync response, Response and DCC Alert. |
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
| `.../adapter/XmlDspMessageReader.java` | Reads `Response` / `DCCAlert` (PLACEHOLDER XML), refuses DTDs. |
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
                         <--Facts {InstantaneousImportRegister=12345.6}--
outcome: {"status":"RESPONDED","facts":{"values":{"InstantaneousImportRegister":"12345.6"}}, ...}
```

| File | What it does |
|---|---|
| `orchestrator/.../entity/Facts.java` | Name -> value, sorted, immutable. |
| `.../usecase/ResponseDecoder.java` | The port. In the model this is **Parsec** (CSW software, GBCS decoding), which we do not have. |
| `.../adapter/PlaceholderResponseDecoder.java` | PLACEHOLDER: each simple element of the placeholder Response `Body` becomes a Fact. |
| `.../adapter/ResponseDecoderActivityImpl.java` | Activity `DecodeResponse`; logs `response-decoded` (the model's "each Response decoded"); a payload it cannot decode fails with non-retryable `ResponseNotDecodable`. |
| `.../adapter/ServiceRequestWorkflowImpl.java` | After the Response: decode, then return `RESPONDED` with the Facts, or `DECODE_FAILED`. |

Changes:
- `ServiceRequestOutcome` returns `facts` instead of the Response XML: the model returns "outcome and Facts", and
  Temporal histories stay smaller.
- New status `DECODE_FAILED`: the device answered, but the Response could not be decoded. It is not sent again.
- Failure details now show the original message (for example A7: "DSP signature on the synchronous response is not
  valid: signature does not match"), without Temporal's `message='...', type='...'` wrapper.

To confirm with DCC / the lead: which Facts each SRV yields (depends on the final Flows) and how Parsec is called. **[CONFIRM]**

## Service interactions

From the architecture model. Implemented so far: `orchestrator` to `duis-signer` (Sign activity, signing in KMS), `orchestrator` to the DSP (simulator),
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
| `batch-ingest` | Temporal | starts the Batch workflow (ID from bucket, key, object version) |
| `orchestrator` | `duis-signer` | Temporal activity on the signing task queue **(working: signs in KMS)** |
| `orchestrator` | DSP Gateway | HTTP POST of the signed Service Request, once, rate-limited **(working: DSP simulator; 1/s, fair by Batch)** |
| `response-listener` | Temporal | signals the Service Request workflow (ID from the Request ID) **(working, after checking the DSP signature)** |
| `orchestrator` | Kafka | publishes step, device and Batch results |
