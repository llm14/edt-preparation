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
- Ports: batch-ingest 8081, orchestrator 8082, duis-signer 8083, response-listener 8084 (8080 is left for the Temporal UI).

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
