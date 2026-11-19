# Design

## Context

What exists today (read from the repo, not assumed):

- `docker-compose.yml` runs the four apps from `edt/<app>:local` images (ports 8081-8084, health at `/q/health/ready`),
  configured only by environment variables, with the certificate store mounted from `dcc-tools/certs`.
- `dcc-tools/docker-compose.yml` runs the emulated DCC side: PostgreSQL, a schema Job, Temporal 1.32.2, a namespace Job,
  the Temporal UI, Floci (KMS, S3, SQS, in memory), `kms-setup` (one shot), the DSP simulator, one Kafka broker, a topics
  Job, and `manifest-upload` (on demand).
- `kms-setup` generates `certificate-store.p12` and `dsp-signing.p12` at every start. Both folders are git-ignored.
- The Docker Desktop cluster (`docker-desktop` context, one node, containerd) is running. `kubectl` and `helm` are
  installed; `kind`, `k3d` and `minikube` are not.
- Model: 300.010 and 300.020 (`architecture-model-sequences-deployment.md`) describe the production shape. This change
  reproduces only the part CLAUDE.md asks for: a minimal setup for running tests.

See proposal.md for motivation and scope.

## Goals / Non-Goals

**Goals:**
- The same images and the same configuration keys as Compose, so a difference in behaviour points at the cluster, not
  at the application.
- No change to application code and, if possible, none to `dcc-tools` code.
- Reuse the existing scripts (Temporal schema, namespace, Kafka topics) instead of copying them.

**Non-Goals:**
- Reproducing production scaling, availability or network policy (see proposal, out of scope).
- A production-grade secret flow. These Secrets hold test keys only.

## Decisions

### 1. Two Helm charts, one per side
`deploy/helm/edt` (the four apps) and `deploy/helm/dcc-tools` (the emulated DCC side).
*Why:* it mirrors the model's split between what we build and what DCC provides, and the DCC side can be installed
alone. *Rejected:* one umbrella chart (mixes ownership and makes the DCC side hard to leave out) and plain manifests
with Kustomize (no templating for the repeated app shape, and the task asks for Helm).

### 2. The four apps are one chart that loops over a values map
Each app is an entry in `values.yaml` (name, port, environment, mounted Secrets). One template renders a Deployment, a
Service and a ConfigMap per entry.
*Why:* the four apps differ only in those values. *Rejected:* four sub-charts (four copies of the same templates).

### 3. Namespaces `edt` and `dcc-tools`
The model names `edt` and `temporal`. Temporal, Kafka, Floci and the simulator all go in `dcc-tools`.
*Why:* fewer moving parts for a test setup. *Rejected:* a separate `temporal` namespace (closer to the model, more
install steps, nothing gained for tests). Marked as an open question.
Because the apps and the DCC side are in different namespaces, addresses in values are full service names
(`temporal.dcc-tools.svc.cluster.local:7233`).

### 4. Local cluster: the Kubernetes in Docker Desktop
*Why:* already enabled, no sign-up, no install. *Rejected:* `kind` or `k3d` (open source and fine, but one more tool to
install for no benefit here).

### 5. Images: the Compose tags, built with the existing build, then loaded into the node
The script runs `./gradlew build` and `docker compose build` (and the dcc-tools equivalents), which produce
`edt/<app>:local`, then loads each image into the cluster node:
`docker save <image> | docker exec -i desktop-control-plane ctr -n k8s.io images import -`.
Charts use `imagePullPolicy: IfNotPresent` and no registry.
*Why:* no second build path to keep in sync, and no registry to run.
*Rejected:* a local registry (more machinery; the load step is enough).

**Spike result (task 1.1, 2026-11-19):** the node does **not** see images built with Docker. Even though Docker Desktop
uses the containerd image store, a pod with `imagePullPolicy: Never` for `edt/orchestrator:local` failed with
`ErrImageNeverPull`. After importing the image into the node with `ctr` (about 12 seconds for the orchestrator image;
the node reports it as `docker.io/edt/orchestrator:local`), the same pod started the application, which then exited
because its certificate store file was missing, as expected with no Secret mounted. The node is a Docker container
named `desktop-control-plane`; `ctr` is available inside it. The imported image stays in the node until the cluster is
reset, so a rebuilt image must be loaded again.

### 6. Secrets: kms-setup runs on the host through a port-forward
The script forwards Floci's port 4566, runs the existing `kms-setup` build on the host against it (it also creates the
KMS keys, the bucket and the queue), then creates two Secrets from the files it wrote: `edt-certificate-store` in `edt`
(and in `dcc-tools` if the simulator needs it) and `dsp-signing-key` in `dcc-tools`. Pods that mount a missing Secret wait
in `ContainerCreating`, so the apps start as soon as the Secrets appear.
*Why:* needs no change to dcc-tools code, no extra RBAC and no `kubectl` image. The same flow as Compose, with the
host replacing the container. *Rejected:* an in-cluster Job with an extra `kubectl` container (RBAC to create Secrets in
two namespaces, one more image to choose) and changing kms-setup to call the Kubernetes API (code change in an
emulator, against the "mock only what the EDT consumes" rule). Cost: the host needs the JDK, which developers have.
`manifest-upload` is run the same way, from the host through the Floci port-forward.

### 7. Start order by retrying, not by hooks
Compose uses `depends_on`. Here the schema Job and the namespace Job are plain Jobs with `restartPolicy: OnFailure`;
the Temporal Deployment restarts until its schema exists; the apps fail their readiness probe and restart until Temporal
answers.
*Why:* Helm hooks run before the rest of a chart, so they cannot wait for PostgreSQL in the same chart. This matches the
fail-fast behaviour the apps already have in Compose (`restart: unless-stopped`). *Rejected:* hook ordering and
init containers that poll other Jobs.

### 8. Existing scripts come into the chart through `--set-file`
The install script passes `dcc-tools/temporal/scripts/*.sh` and `dcc-tools/kafka/create-topics.sh` to Helm, which puts
them in ConfigMaps mounted by the Jobs. *Why:* Helm cannot read files outside the chart directory, and symlinks are
unreliable on Windows. A copy would drift. Both scripts use `temporal:7233` and `kafka:9092`, which resolve inside the
`dcc-tools` namespace, so they run unchanged.

### 9. Kafka advertises its full service name
The broker's internal listener advertises `kafka.dcc-tools.svc.cluster.local:9092`, not `kafka:9092`.
*Why:* Kafka clients reconnect to the advertised address. From the `edt` namespace the short name does not resolve, so the
Orchestrator would connect to the bootstrap address and then fail. Compose never shows this.

### 10. `enableServiceLinks: false` on every pod
*Why:* Kubernetes injects variables such as `KAFKA_PORT=tcp://...` for a Service named `kafka`, which collide with the
`KAFKA_*` settings the image reads. Switching service links off removes the clash.

### 11. Probes use the endpoints the apps already have
Readiness `/q/health/ready`, liveness `/q/health/live`, plus a startup probe so slow starts are not killed. The README
already says liveness exists for Kubernetes.

### 12. Access from the host by port-forward
Services are `ClusterIP`. The script offers a `forward` command for the Temporal UI (8088), the app health ports and
Floci. *Why:* no extra exposure and the same ports as Compose. *Rejected:* LoadBalancer or NodePort services (they work
on Docker Desktop but make the setup depend on that product).

### Deliberate differences from the model (CLAUDE.md allows a minimal test setup)
| Model (300.010 / 300.020) | This change | Reason |
|---|---|---|
| At least two pods per Deployment | One pod | Local test setup |
| KEDA, HPA, PodDisruptionBudget | None | Production scaling, not needed to test interactions |
| NetworkPolicy `edt` | None | The Docker Desktop network plugin may not enforce it |
| IAM role per ServiceAccount, no AWS credential in cluster | Test credentials for Floci (`test`/`test`) | Floci is an emulator, no real AWS |
| Secret `edt-kafka-client` (mutual TLS, to be confirmed) | None, plain-text Kafka | Not confirmed with DCC |
| DCC secrets operator copies Secrets | The install script creates them | DCC platform part |

## Risks / Trade-offs

- **Floci loses everything on restart** (KMS keys, bucket, queue) and the Secrets then no longer match its keys →
  document "re-run the install's secrets step, or reinstall"; no automation in this change.
- **Local images are not visible to the cluster node** (confirmed by the spike) → the script loads each image into the
  node. This depends on the node container being named `desktop-control-plane` and having `ctr`; the script checks both
  and stops with a clear message if not (for example after Docker Desktop changes how it provisions the cluster).
- **Host-side kms-setup needs the JDK and a free port 4566** → documented prerequisite; the script checks the port.
- **Docker Desktop Kubernetes can be switched off or reset** → the script checks the `docker-desktop` context and refuses
  to run against any other context, so it cannot touch a real cluster by mistake.
- **Compose and cluster configuration can drift** → the spec requires the same keys; a task compares them.

## Open Questions

- Model 300.010: should Temporal sit in its own `temporal` namespace as the model shows? Deferrable: changes values and
  one chart, not the specs or tasks.
- Model 300.020 and CLAUDE.md open point 4: Kafka client authentication by mutual TLS is not part of this change.
- Container images at DCC (ECR, image build method) is not confirmed in the model; `:local` images decide nothing about it.
- Should the cluster setup replace Compose later? Out of scope here; Compose stays.
