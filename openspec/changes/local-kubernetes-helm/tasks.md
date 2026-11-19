# Tasks

## 1. Spike and guard rails

- [x] 1.1 Spike: build one image (`docker compose build orchestrator`), start a throw-away pod with `imagePullPolicy: Never` on the `docker-desktop` cluster, and record in design.md whether the node sees the local image. Verify: the pod reaches `Running`, or the failure and chosen fallback (load into node, or local registry) are written down.
- [x] 1.2 Create `deploy/` with a script `deploy/edt.sh` skeleton that stops unless `kubectl config current-context` is `docker-desktop`. Verify: running it with another context selected (`kubectl config use-context` to a dummy) exits non-zero and changes nothing.

## 2. Emulated DCC side chart (`deploy/helm/dcc-tools`)

- [ ] 2.1 Add PostgreSQL, the `temporal-schema` Job, the Temporal Server (with `enableServiceLinks: false`), the `temporal-namespace` Job and the Temporal UI. The Jobs mount the scripts from `dcc-tools/temporal/scripts` through `--set-file`. Verify: `helm lint` passes, and after install both Jobs show `Completed`, Temporal is ready, and the UI port-forward shows namespace `edt`.
- [ ] 2.2 Add Floci with its Service on 4566. Verify: `kubectl port-forward` to 4566 answers, and the pod is ready.
- [ ] 2.3 Add the Kafka broker advertising `kafka.dcc-tools.svc.cluster.local:9092`, and the topics Job using `dcc-tools/kafka/create-topics.sh`. Keep the placeholder comments about topics and authentication from the Compose file, marked [CONFIRM]. Verify: the topics Job completes, and `kafka-topics.sh --list` from a pod in the `edt` namespace returns the three `edt.*` topics.
- [ ] 2.4 Add the DSP simulator, mounting the `dsp-signing-key` Secret read-only and sending Responses to the Response Listener's full service name (value in `values.yaml`). Verify: the pod waits in `ContainerCreating` while the Secret is missing and becomes ready once it exists (after task 3.1).
- [ ] 2.5 Document the chart (what it emulates, what each value means) in `deploy/helm/dcc-tools/README.md`. Verify: every value in `values.yaml` is described, and the placeholders for Kafka topics and authentication are named.

## 3. Keys and certificates

- [ ] 3.1 Add the secrets step to `deploy/edt.sh`: port-forward Floci, run the existing `kms-setup` build on the host against it with the same variables as Compose, then create `edt-certificate-store` (namespace `edt`) and `dsp-signing-key` (namespace `dcc-tools`) from the files. Verify: `kubectl get secret` shows both, and `kms-setup` has also created the bucket and queue (Floci answers a list-buckets call).
- [ ] 3.2 Verify nothing is committed: after the step, `git status --short` shows no change and `git ls-files dcc-tools/certs dcc-tools/dsp-keys` prints nothing.

## 4. EDT applications chart (`deploy/helm/edt`)

- [ ] 4.1 Add the looped Deployment, Service and ConfigMap for the four apps with `enableServiceLinks: false`, `runAsNonRoot`, and startup, readiness (`/q/health/ready`) and liveness (`/q/health/live`) probes. Mount the certificate store read-only only into Orchestrator, DUIS Signer and Response Listener. Verify: `helm lint` and `helm template` pass, and in the rendered output Batch Ingest has no Secret volume.
- [ ] 4.2 Set the apps' environment from the same keys and values as `docker-compose.yml`, with service names replaced by full cluster names. Verify: a small check (script or Helm unit test) compares the key names per app against `docker-compose.yml` and reports any key missing or extra.
- [ ] 4.3 Document the chart in `deploy/helm/edt/README.md`, including the table of deliberate differences from the model (from design.md). Verify: the table in the README matches design.md.

## 5. Install, remove and failure behaviour

- [ ] 5.1 Complete `deploy/edt.sh` with `up` (build images, load them into the node as in design decision 5, install the DCC chart, secrets step, install the EDT chart, wait for ready), `down` (remove both releases and namespaces) and `forward`. Verify: on a cluster with no EDT objects, `up` ends successfully with all four apps ready and `down` leaves neither namespace.
- [ ] 5.2 Verify the failure scenarios of the spec: scale Temporal to zero and check that the apps are not ready and are restarted; make one app unready and check that `up` names it and fails within its time limit.
- [ ] 5.3 Add a README section "Run on a local cluster" (prerequisites, `up`, `down`, `forward`, what to do after a Floci restart). Verify: following it on a clean checkout needs no step that is not written there.

## 6. End-to-end check

- [ ] 6.1 Run the happy path on the cluster: upload the one-device sample Manifest with `manifest-upload` on the host through the Floci port-forward, as in HAPPY-PATH-MANUAL.md. Verify: Batch, Page and Flow workflows complete in the Temporal UI, and a Batch result event is on the Batch-results Kafka topic.
- [ ] 6.2 Verify the asynchronous Response: after the simulator's delay the Response Listener logs the signal and the Service Request workflow finishes. Record any difference from the Compose run in HAPPY-PATH-MANUAL.md as a "cluster" note.

## Workflow follow-up

- Review and merge, then archive this change so `specs/local-cluster/` becomes a main spec.
- Decide the open questions in design.md (a separate `temporal` namespace, Kafka mutual TLS) when DCC answers.
