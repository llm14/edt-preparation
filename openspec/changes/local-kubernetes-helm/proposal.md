# Proposal

## Why

Today the four EDT applications and the emulated DCC side only run with two Docker Compose files. The architecture model
(300.010 and 300.020) describes the EDT as Deployments in a Kubernetes cluster, and CLAUDE.md says we write Kubernetes
scripts for a minimal setup that lets us run tests. Nothing in the repo exercises that shape yet: Services, ConfigMaps,
Secrets, readiness probes and pod restarts are untested. A local cluster, installed with Helm, lets us run the container
interactions (the focus of 100.010) in the same form they will have at DCC, and gives the team one command to bring the
whole system up.

## What Changes

- Add Helm charts under a new top-level `deploy/` folder (outside Gradle, as the model notes suggest):
  - a chart for the four EDT applications (`batch-ingest`, `orchestrator`, `duis-signer`, `response-listener`);
  - a chart for the emulated DCC side now in `dcc-tools/docker-compose.yml` (Temporal with PostgreSQL and UI, Floci,
    Kafka and its topics, DSP simulator). `kms-setup` is not in the chart: it runs on the host (see below).
- Run the apps from the locally built `edt/<app>:local` images, with the same configuration keys the Compose file sets
  (`EDT_TEMPORAL_TARGET`, `EDT_AWS_ENDPOINT`, `EDT_KAFKA_BOOTSTRAP_SERVERS` and so on), delivered by ConfigMaps.
- Deliver the certificate store and the DSP key as Kubernetes Secrets. The install script runs the existing `kms-setup`
  on the host against Floci through a port-forward and creates the Secrets from the files it writes, because kms-setup
  generates them at every start (they are git-ignored today and shared through a bind mount).
- Use the readiness and liveness endpoints the apps already expose (`/q/health/ready`, `/q/health/live`) as probes.
- Add a short script (and README section) to build images, install both charts, wait for readiness and remove everything.
- Docker Compose stays as it is. The cluster is an addition, not a replacement.

## Capabilities

### New Capabilities

- `local-cluster`: how the whole system is brought up and checked on a local Kubernetes cluster: what must be running,
  when it counts as ready, what the cluster does when an app cannot reach Temporal, and that no account or paid tool is
  needed. It spans all four containers and the emulated DCC side, so it is cross-cutting.

### Modified Capabilities

None. There are no specs yet.

## Impact

- New files only: `deploy/` (charts, values, script) and a README section. No application Java code changes.
- `dcc-tools/` is read for what to emulate, not changed. If the certificate hand-over needs a change to kms-setup, that
  is listed as an open question and would be its own decision.
- Needs on the developer machine: Docker Desktop with Kubernetes enabled (the `docker-desktop` context exists here),
  `kubectl` and `helm` (both installed here). No account is needed.
- Model fit: the model's namespace `edt` is kept for the four apps. The DCC-provided parts get their own namespace.
  Everything marked in the model as DCC platform or AWS (KEDA, HPA, PodDisruptionBudget, node autoscaler, IAM roles,
  secrets operator) is not reproduced.

## Out of scope

- Production or PIT/SIT deployment, ECR, real AWS, KEDA scalers, HorizontalPodAutoscaler, PodDisruptionBudget, IAM roles
  for ServiceAccounts, the DCC secrets operator, the log agent DaemonSet, the mesh proxy.
- At least two pods per Deployment (the model's production shape). Locally each Deployment runs one pod.
- Replacing Docker Compose, CI pipelines, and changing any application code.
- Flow content and the open points in CLAUDE.md.

## Assumptions made (confirm or correct)

1. "All apps" means the four EDT applications and the emulated DCC side, because the applications cannot work without
   Temporal, Kafka, Floci and the DSP simulator.
2. The local cluster is the Kubernetes that comes with Docker Desktop (context `docker-desktop`), because it is already
   enabled here and `kind`, `k3d` and `minikube` are not installed.
3. The emulated DCC side lives in a second namespace (`dcc-tools`), separate from `edt`, to mirror the model's split
   between what we build and what DCC provides.

## Open questions

- **Image visibility.** The cluster node reports a `containerd` runtime. Whether images built with `docker build` are
  visible to it without a registry is not verified. If not, the choice is between loading images into the node and a
  small local registry. This is a spike task.
- **Certificate hand-over.** kms-setup writes `certificate-store.p12` and `dsp-signing.p12` to a folder. In a cluster the
  apps need them as Secrets. The plan runs kms-setup on the host and creates the Secrets with the install script (needs
  the JDK and a free port 4566). The alternatives are an in-cluster Job (needs RBAC and a `kubectl` image) and kms-setup
  talking to the Kubernetes API itself (a code change in dcc-tools). See design.md, decision 6.
- **Floci is in memory.** A Floci pod restart loses KMS keys, the bucket and the queue, so the Secrets no longer match.
  How to recover (re-run the Job, or accept a full reinstall) needs a decision.
- **Namespace for Temporal.** The model has a `temporal` namespace. This plan puts Temporal, Kafka, Floci and the
  simulator together in `dcc-tools`. Splitting Temporal out is possible but adds setup.
- **Kafka authentication.** Plain text locally, as in Compose. Mutual TLS is still to be confirmed with DCC (open point
  in CLAUDE.md) and is not part of this change.
- **Container images at DCC (ECR, image build method)** is not confirmed in the model, so the `:local` images here do not
  decide it.
