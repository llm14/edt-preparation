# local-cluster Specification

## Purpose

Defines how the four EDT applications and the emulated DCC side are brought up, checked and removed on a local Kubernetes cluster, so the container interactions can be exercised in the same form they have at DCC.

## Requirements

### Requirement: One command brings the system up
The system SHALL provide one documented command that builds the application images, installs the emulated DCC side and the four EDT applications on the local cluster, and waits until every one of them reports ready.

#### Scenario: Clean cluster
- **GIVEN** a local cluster with none of the EDT objects installed
- **WHEN** the developer runs the install command
- **THEN** the emulated DCC side is installed before the applications
- **AND** the command ends successfully only when all four applications report ready

#### Scenario: Install fails
- **WHEN** an application does not become ready within the command's time limit
- **THEN** the command ends with a failure and names the application that is not ready

### Requirement: One command removes the system
The system SHALL provide one documented command that removes everything the install command created, leaving no EDT object in the cluster.

#### Scenario: Remove after install
- **GIVEN** the system is installed
- **WHEN** the developer runs the remove command
- **THEN** the EDT namespace and the emulated DCC namespace no longer exist

### Requirement: Applications use the same configuration as the Compose setup
Each application SHALL receive the same configuration keys, with the same meaning, that the Docker Compose setup gives it, so the same application image runs in both without a code or image change.

#### Scenario: Same image in both setups
- **WHEN** an application starts in the cluster
- **THEN** it runs the same `edt/<application>:local` image that Docker Compose runs
- **AND** it reaches Temporal, Floci, Kafka and the DSP simulator through the addresses its configuration gives

### Requirement: Readiness reflects the application's own health
An application SHALL be treated as ready only when its own readiness endpoint reports UP, and SHALL be restarted by the cluster when its liveness endpoint stops answering.

#### Scenario: Temporal is unreachable
- **GIVEN** the Temporal Server is not reachable
- **WHEN** an application starts
- **THEN** it does not become ready
- **AND** the cluster restarts it if the process exits, as Docker Compose does

### Requirement: Keys and certificates are never part of the images or the repository
The certificate store and the DSP signing key SHALL reach the pods that need them as read-only Secrets created at install time. The DSP signing key SHALL reach only the DSP simulator.

#### Scenario: Secrets are created by the install
- **WHEN** the install command runs on a clean cluster
- **THEN** the Secrets exist before the pods that mount them start
- **AND** the Orchestrator, DUIS Signer and Response Listener mount the certificate store read-only
- **AND** no EDT application mounts the DSP signing key

#### Scenario: Nothing committed
- **WHEN** the repository is searched for the generated certificate store and DSP key
- **THEN** neither file is tracked by git

### Requirement: A Manifest travels end to end on the cluster
With the system installed, uploading a signed sample Manifest SHALL result in Batch, device and step results published to Kafka, with the same outcome the Docker Compose setup gives for the same Manifest.

#### Scenario: Happy path with the one-device sample
- **GIVEN** the system is installed and ready
- **WHEN** the developer uploads the one-device sample Manifest from `samples/manifests/` as described in HAPPY-PATH-MANUAL.md
- **THEN** a Batch workflow, a Page workflow and a Flow workflow complete in Temporal
- **AND** a Batch result event appears on the Kafka topic for Batch results

#### Scenario: Asynchronous Response is delivered
- **WHEN** the DSP simulator sends the delayed Response
- **THEN** it reaches the Response Listener through its cluster address
- **AND** the waiting Service Request workflow is signalled

### Requirement: Cluster tooling needs no account
The local setup SHALL use only open-source or free tools that need no sign-up, and SHALL work with the Kubernetes that comes with Docker Desktop.

#### Scenario: Fresh developer machine
- **GIVEN** Docker Desktop with Kubernetes enabled, `kubectl` and `helm` installed
- **WHEN** the developer follows the README section
- **THEN** no registry login, cloud account or licence key is requested

### Requirement: Emulated DCC settings stay marked as placeholders
The emulated DCC side SHALL keep the settings that DCC has not confirmed (Kafka topic names and settings, plain-text Kafka without authentication) visibly marked as placeholders in its configuration. [CONFIRM] against DCC's cluster.

#### Scenario: Reading the configuration
- **WHEN** a developer opens the emulated Kafka configuration
- **THEN** the topic names and the lack of authentication are marked as not confirmed with DCC
