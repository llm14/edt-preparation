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

## Service interactions

Planned, from the architecture model. Not implemented yet.

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
| `orchestrator` | `duis-signer` | Temporal activity on the signing task queue |
| `orchestrator` | DSP Gateway | HTTP POST of the signed Service Request, once, rate-limited |
| `response-listener` | Temporal | signals the Service Request workflow (ID from the Request ID) |
| `orchestrator` | Kafka | publishes step, device and Batch results |
