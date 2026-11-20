# dcc-tools chart

The DCC side of the EDT, for a **local test cluster only**. In production DCC provides and runs all of this; we emulate
only what the four EDT applications consume (see `dcc-tools/docker-compose.yml`, which this chart mirrors).

Installed by `deploy/edt.sh up` into the namespace `dcc-tools`. The four EDT applications go in `edt`
(`deploy/helm/edt`), so every address the apps use is a full service name such as
`temporal.dcc-tools.svc.cluster.local:7233`.

## What it emulates

| Object | Emulates | Notes |
|---|---|---|
| `postgresql` | Temporal's database | No volume: a restart starts empty, as in Compose |
| `temporal-schema` (Job) | Temporal schema set-up | Retries until PostgreSQL answers |
| `temporal` | Temporal Server (service `temporal`, port 7233) | Restarts until its schema exists |
| `temporal-namespace` (Job) | creates the Temporal namespace for the EDT | Waits for the server itself |
| `temporal-ui` | Temporal Web UI (service port 8088) | Reach it with a port-forward |
| `floci` | AWS KMS, S3 and SQS (service `floci`, port 4566) | In memory: a restart loses keys, Manifests and messages |
| `kafka` | one Kafka broker (service `kafka`) | See "Placeholders" below |
| `kafka-topics` (Job) | creates the EDT topics | Retries until the broker answers |
| `kafka-ui` | a web page to browse the topics (service port 8089) | A developer tool, not part of the model. Reach it with a port-forward |
| `dsp-simulator` | the DSP HTTPS Gateway, the DSP and the devices | Needs the Secret `dsp-signing-key` |

The Secrets (the certificate store and the DSP key) are not part of the chart. `deploy/edt.sh` creates them from the
files `kms-setup` writes (see the change `local-kubernetes-helm`, design decision 6). Until `dsp-signing-key` exists the
simulator pod waits in `ContainerCreating`.

## Values

All values are for a local test cluster. `--set-file` values must be passed; the templates stop with a message if one is
missing, and `deploy/edt.sh` passes them.

| Value | Default | What it is |
|---|---|---|
| `scripts.setupSchema` | (`--set-file`) | The script `dcc-tools/temporal/scripts/setup-schema.sh`, mounted by the `temporal-schema` Job |
| `scripts.createNamespace` | (`--set-file`) | The script `dcc-tools/temporal/scripts/create-namespace.sh`, mounted by the `temporal-namespace` Job |
| `scripts.createTopics` | (`--set-file`) | The script `dcc-tools/kafka/create-topics.sh`, mounted by the `kafka-topics` Job |
| `postgresql.image` | `postgres:16` | PostgreSQL image |
| `postgresql.user` | `temporal` | Database user (test credential, as in Compose) |
| `postgresql.password` | `temporal` | Database password (test credential, as in Compose) |
| `floci.image` | `floci/floci:2.4.0` | The AWS emulator image (open source, no account) |
| `kafkaUi.image` | `ghcr.io/kafbat/kafka-ui:v1.4.2` | Kafka UI image (Kafbat, Apache-2.0, no account) |
| `kafka.image` | `apache/kafka:4.1.0` | Kafka image, also used by the topics Job |
| `kafka.clusterDomain` | `cluster.local` | The cluster's DNS suffix. The broker advertises `kafka.<namespace>.svc.<clusterDomain>:9092`, because the short name `kafka` does not resolve from the `edt` namespace |
| `dspSimulator.image` | `edt/dsp-simulator:local` | Built by `dcc-tools`; `deploy/edt.sh` loads it into the cluster node |
| `dspSimulator.responseListenerUrl` | `http://response-listener.edt.svc.cluster.local:8084/duis/responses` | Where the simulator sends the delayed Responses |
| `dspSimulator.responseDelaySeconds` | `5` | How long the simulator waits before it sends a Response |
| `dspSimulator.keySecret` | `dsp-signing-key` | Name of the Secret that holds the DSP's private key (file `dsp-signing.p12`) |
| `dspSimulator.keystorePassword` | `edt-test-only` | Password of that key store (test only) |
| `temporal.serverImage` | `temporalio/server:1.32.2` | Temporal Server image |
| `temporal.adminToolsImage` | `temporalio/admin-tools:1.32.2` | Image of the two one-shot Jobs |
| `temporal.uiImage` | `temporalio/ui:2.58.1` | Temporal Web UI image |
| `temporal.edtNamespace` | `edt` | The Temporal namespace the EDT's workflows live in |
| `temporal.dynamicConfig` | (`--set-file`) | The file `dcc-tools/temporal/dynamicconfig/development-sql.yaml` (task queue fairness and partitions) |

## Placeholders (not confirmed with DCC) [CONFIRM]

- **Kafka topic names and settings.** The topics `edt.flow-steps`, `edt.device-results` and `edt.batch-results`, with 3
  partitions, replication factor 1 and 7 days of retention, come from `dcc-tools/kafka/create-topics.sh`. DCC owns the
  real cluster and its topics.
- **Kafka authentication.** There is none: plain text only. In production the connection is expected to use mutual TLS,
  to be confirmed with DCC (open point 4 in `CLAUDE.md`).

## Known limits

- One pod per Deployment and no autoscaling, PodDisruptionBudget or NetworkPolicy. The change's design lists each
  difference from the architecture model.
- Floci and PostgreSQL keep nothing across a restart. After a Floci restart the keys no longer match the Secrets: re-run
  the secrets step of `deploy/edt.sh` or reinstall.
