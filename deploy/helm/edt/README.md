# edt chart

The four EDT applications on a **local test cluster**: Batch Ingest, Orchestrator, DUIS Signer and Response Listener.
Installed by `deploy/edt.sh up` into the namespace `edt`. The emulated DCC side (Temporal, Kafka, Floci, the DSP
simulator) is the other chart, `deploy/helm/dcc-tools`, in the namespace `dcc-tools`.

## What it renders

One template, `templates/apps.yaml`, loops over the map `apps` in `values.yaml`. For each app it renders:

| Object | Notes |
|---|---|
| ConfigMap `<app>` | The app's environment variables, from `apps.<app>.env` |
| Deployment `<app>` | One pod, image `edt/<app>:local`, runs as a non-root user, no privilege escalation, all capabilities dropped |
| Service `<app>` | ClusterIP on the app's port. The simulator reaches the Response Listener through `response-listener.edt.svc.cluster.local:8084` |

Probes use the endpoints the apps already expose: a startup probe and the liveness probe on `/q/health/live`, the
readiness probe on `/q/health/ready`. An app that cannot reach Temporal is not ready, and the cluster restarts it if the
process exits, as Docker Compose does.

The certificate store (certificates only, no private keys) is the Secret `edt-certificate-store`, created by
`deploy/edt.sh secrets`. It is mounted read-only at `/certs` into the Orchestrator, the DUIS Signer and the Response
Listener. Batch Ingest does not mount it. The DSP's private key is never mounted by an EDT app.

## Values

| Value | Default | What it is |
|---|---|---|
| `image.repository` | `edt` | The image of an app is `<repository>/<app>:<tag>` |
| `image.tag` | `local` | Image tag. `deploy/edt.sh` builds the images and loads them into the cluster node |
| `image.pullPolicy` | `IfNotPresent` | Nothing is pulled from a registry |
| `certificateStore.secretName` | `edt-certificate-store` | The Secret that holds `certificate-store.p12` |
| `certificateStore.mountPath` | `/certs` | Where the Secret is mounted |
| `apps.<app>.port` | 8081 to 8084 | The app's HTTP port: its Service, probes and health |
| `apps.<app>.certificateStore` | `true`, except Batch Ingest | Whether the app mounts the certificate store |
| `apps.<app>.env` | see `values.yaml` | The app's environment, the same keys as `docker-compose.yml` |

The environment is the one Compose gives each app. The only difference is that the emulated DCC side is reached by its
full service name (`temporal.dcc-tools.svc.cluster.local:7233` and so on), because it is in another namespace.
**`deploy/check-env-keys.sh` checks this**: it compares each app's keys and values with `docker-compose.yml` and reports
any key that is missing, extra or different. Run it after changing either side.

## Deliberate differences from the architecture model

The model (300.010 and 300.020) describes the production shape. CLAUDE.md asks for a minimal setup that lets us run
tests, so this chart differs from the model on purpose:

| Model (300.010 / 300.020) | This change | Reason |
|---|---|---|
| At least two pods per Deployment | One pod | Local test setup |
| KEDA, HPA, PodDisruptionBudget | None | Production scaling, not needed to test interactions |
| NetworkPolicy `edt` | None | The Docker Desktop network plugin may not enforce it |
| IAM role per ServiceAccount, no AWS credential in cluster | Test credentials for Floci (`test`/`test`) | Floci is an emulator, no real AWS |
| Secret `edt-kafka-client` (mutual TLS, to be confirmed) | None, plain-text Kafka | Not confirmed with DCC |
| DCC secrets operator copies Secrets | The install script creates them | DCC platform part |
