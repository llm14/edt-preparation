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

## Service interactions

To be filled in as each application is added.
