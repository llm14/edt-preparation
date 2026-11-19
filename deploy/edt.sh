#!/usr/bin/env bash
# Brings the EDT up on the local Docker Desktop Kubernetes cluster, and removes it again.
#
#   deploy/edt.sh up        build images, install both charts, wait until the four apps are ready
#   deploy/edt.sh down      remove both charts and their namespaces
#   deploy/edt.sh forward   port-forward the Temporal UI, the app health ports and Floci
#   deploy/edt.sh secrets   (re)create the KMS keys, the bucket, the queue and the two Secrets (also part of "up")
#
# Settings (environment variables, all optional):
#   EDT_UP_TIMEOUT          seconds "up" waits for the four apps to be ready (default 300)
#   EDT_FLOCI_LOCAL_PORT    local port for the Floci port-forward used by "secrets" (default 14566)
#   EDT_HELM_ARGS           extra arguments for the "helm upgrade --install" of the edt chart, for example
#                           --set apps.orchestrator.env.EDT_TEMPORAL_TARGET=other:7233 (used to try failures)
#
# The script only ever touches the namespaces "edt" and "dcc-tools".
#
# Only the "docker-desktop" kubectl context is accepted. Anything else (a work or cloud cluster, for example) makes
# the script stop before it runs a single cluster command. See openspec/changes/local-kubernetes-helm/design.md.
set -euo pipefail

REQUIRED_CONTEXT="docker-desktop"

die() {
  echo "edt.sh: $*" >&2
  exit 1
}

# Every command starts here, so no command can touch another cluster.
require_context() {
  command -v kubectl >/dev/null 2>&1 || die "kubectl is not installed"
  local current
  current="$(kubectl config current-context 2>/dev/null || true)"
  if [ "$current" != "$REQUIRED_CONTEXT" ]; then
    die "kubectl context is '${current:-none}', not '$REQUIRED_CONTEXT'. Nothing was changed." \
        "Switch with: kubectl config use-context $REQUIRED_CONTEXT"
  fi
}

usage() {
  echo "Usage: deploy/edt.sh {up|down|forward|secrets}" >&2
  exit 2
}

# ---------------------------------------------------------------------------------------------------------------
# Keys and certificates (design decision 6)
#
# kms-setup (the existing dcc-tools build) creates the KMS keys, the Manifest bucket and the queue in Floci, and writes
# two PKCS#12 files. It runs HERE, on the host, against Floci through a port-forward, then the files become Secrets:
#   edt-certificate-store  namespace edt         certificates only; Orchestrator, DUIS Signer, Response Listener mount it
#   dsp-signing-key        namespace dcc-tools   the simulated DSP's PRIVATE key; only the DSP simulator mounts it
#
# Every run makes a NEW test CA and DSP key (the KMS keys are reused if they exist), so both Secrets are always replaced
# together. Pods read them at start: restart the apps and the simulator after a re-run.
# The files go to a temporary folder that is deleted at the end, so nothing is left on disk or in git, and the files
# Compose uses in dcc-tools/certs are not touched.
# ---------------------------------------------------------------------------------------------------------------

# Git Bash on Windows rewrites arguments that look like Unix paths (for example /opt/kafka/...) before kubectl sees
# them. Switch that off, and hand native programs Windows-style paths ourselves.
export MSYS_NO_PATHCONV=1

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"   # the chart and --set-file paths below are relative to the repository root
EDT_NAMESPACE="edt"
DCC_NAMESPACE="dcc-tools"
FLOCI_LOCAL_PORT="${EDT_FLOCI_LOCAL_PORT:-14566}"   # a local port that is not 4566, so a Compose Floci does not clash
TEST_PASSWORD="edt-test-only"                       # the same test password as Compose and the chart values

native_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

PORT_FORWARD_PID=""
WORK_DIR=""
cleanup() {
  [ -z "$PORT_FORWARD_PID" ] || { kill "$PORT_FORWARD_PID" 2>/dev/null || true; wait "$PORT_FORWARD_PID" 2>/dev/null || true; }
  [ -z "$WORK_DIR" ] || rm -rf "$WORK_DIR"
}

secrets_step() {
  trap cleanup EXIT

  echo "Waiting for Floci to be ready..."
  kubectl -n "$DCC_NAMESPACE" rollout status deploy/floci --timeout=180s \
    || die "Floci is not ready in namespace $DCC_NAMESPACE. Install the dcc-tools chart first."

  echo "Building kms-setup..."
  (cd "$REPO_ROOT/dcc-tools" && ./gradlew -q :kms-setup:installDist) || die "could not build kms-setup"

  echo "Forwarding Floci to localhost:$FLOCI_LOCAL_PORT..."
  kubectl -n "$DCC_NAMESPACE" port-forward svc/floci "$FLOCI_LOCAL_PORT:4566" >/dev/null 2>&1 &
  PORT_FORWARD_PID=$!
  local answered=""
  for _ in $(seq 1 30); do
    # The shell redirects the output: with MSYS_NO_PATHCONV=1 a "-o /dev/null" argument would reach the Windows curl as is.
    if curl -s "http://localhost:$FLOCI_LOCAL_PORT/" >/dev/null; then answered=yes; break; fi
    kill -0 "$PORT_FORWARD_PID" 2>/dev/null || die "port-forward stopped. Is local port $FLOCI_LOCAL_PORT already in use?" \
      "Set EDT_FLOCI_LOCAL_PORT to another port."
    sleep 1
  done
  [ -n "$answered" ] || die "Floci did not answer on localhost:$FLOCI_LOCAL_PORT"

  WORK_DIR="$(mktemp -d)"
  local certificate_store="$WORK_DIR/certificate-store.p12"
  local dsp_keystore="$WORK_DIR/dsp-signing.p12"

  echo "Running kms-setup against Floci..."
  EDT_KMS_ENDPOINT="http://localhost:$FLOCI_LOCAL_PORT" \
  AWS_REGION="eu-west-2" AWS_ACCESS_KEY_ID="test" AWS_SECRET_ACCESS_KEY="test" \
  EDT_CERT_STORE="$(native_path "$certificate_store")" EDT_CERT_STORE_PASSWORD="$TEST_PASSWORD" \
  EDT_DSP_KEYSTORE="$(native_path "$dsp_keystore")" EDT_DSP_KEYSTORE_PASSWORD="$TEST_PASSWORD" \
  EDT_MANIFEST_BUCKET="edt-manifests" EDT_MANIFEST_QUEUE="edt-manifest-events" \
    "$REPO_ROOT/dcc-tools/kms-setup/build/install/kms-setup/bin/kms-setup" || die "kms-setup failed"
  [ -s "$certificate_store" ] && [ -s "$dsp_keystore" ] || die "kms-setup did not write both key stores"

  # Create or replace both Secrets together. apply makes this safe to run again.
  kubectl create namespace "$EDT_NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  kubectl -n "$EDT_NAMESPACE" create secret generic edt-certificate-store \
    --from-file="certificate-store.p12=$(native_path "$certificate_store")" --dry-run=client -o yaml | kubectl apply -f -
  kubectl -n "$DCC_NAMESPACE" create secret generic dsp-signing-key \
    --from-file="dsp-signing.p12=$(native_path "$dsp_keystore")" --dry-run=client -o yaml | kubectl apply -f -
  echo "Secrets are in place. Restart the apps and the DSP simulator if they were already running."
}

# ---------------------------------------------------------------------------------------------------------------
# up, down, forward
# ---------------------------------------------------------------------------------------------------------------

# Docker Desktop runs the cluster node as a container with this name; the images are loaded into it (design decision 5).
NODE_CONTAINER="desktop-control-plane"
UP_TIMEOUT="${EDT_UP_TIMEOUT:-300}"
APPS=(batch-ingest orchestrator duis-signer response-listener)
# "image|build folder": the four apps and the DSP simulator.
IMAGES=(
  "edt/batch-ingest:local|batch-ingest"
  "edt/orchestrator:local|orchestrator"
  "edt/duis-signer:local|duis-signer"
  "edt/response-listener:local|response-listener"
  "edt/dsp-simulator:local|dcc-tools/dsp-simulator"
)

check_prerequisites() {
  local tool
  for tool in docker helm kubectl; do
    command -v "$tool" >/dev/null 2>&1 || die "$tool is not installed"
  done
  docker exec "$NODE_CONTAINER" ctr version >/dev/null 2>&1 \
    || die "cannot reach the cluster node: 'docker exec $NODE_CONTAINER ctr version' failed." \
           "Is Kubernetes enabled in Docker Desktop? Images are loaded into this node (design decision 5)."
}

build_images() {
  echo "Building the applications (tests are skipped here; run ./gradlew test for them)..."
  ./gradlew -q build -x test || die "the application build failed"
  (cd dcc-tools && ./gradlew -q installDist) || die "the dcc-tools build failed"
  local entry
  for entry in "${IMAGES[@]}"; do
    echo "Building image ${entry%%|*}..."
    docker build -q -t "${entry%%|*}" "$(native_path "$REPO_ROOT/${entry##*|}")" >/dev/null \
      || die "docker build failed for ${entry%%|*}"
  done
}

# The node does not see images built with Docker, so each one is imported into the node's containerd.
load_images() {
  local entry image
  for entry in "${IMAGES[@]}"; do
    image="${entry%%|*}"
    echo "Loading $image into the cluster node..."
    docker save "$image" | docker exec -i "$NODE_CONTAINER" ctr -n k8s.io images import - >/dev/null \
      || die "could not load $image into the node"
  done
}

install_dcc_chart() {
  echo "Installing the emulated DCC side (namespace $DCC_NAMESPACE)..."
  # --reset-values: an upgrade starts from the chart's own values, never from what an earlier run was given
  # (this Helm otherwise keeps a previous --set, so a plain "up" would silently keep an old override).
  helm upgrade --install dcc-tools deploy/helm/dcc-tools -n "$DCC_NAMESPACE" --create-namespace --reset-values \
    --set-file scripts.setupSchema=dcc-tools/temporal/scripts/setup-schema.sh \
    --set-file scripts.createNamespace=dcc-tools/temporal/scripts/create-namespace.sh \
    --set-file scripts.createTopics=dcc-tools/kafka/create-topics.sh \
    --set-file temporal.dynamicConfig=dcc-tools/temporal/dynamicconfig/development-sql.yaml >/dev/null \
    || die "helm could not install the dcc-tools chart"
}

install_edt_chart() {
  echo "Installing the four EDT applications (namespace $EDT_NAMESPACE)..."
  # shellcheck disable=SC2086   # EDT_HELM_ARGS is meant to be split into words
  helm upgrade --install edt deploy/helm/edt -n "$EDT_NAMESPACE" --create-namespace --reset-values \
    ${EDT_HELM_ARGS:-} >/dev/null \
    || die "helm could not install the edt chart"
}

# Waits for all four apps at the same time, each for up to EDT_UP_TIMEOUT seconds, then names the ones that are not ready.
wait_apps_ready() {
  echo "Waiting up to ${UP_TIMEOUT}s for the four applications to be ready..."
  local pids=() app i failed=()
  for app in "${APPS[@]}"; do
    kubectl -n "$EDT_NAMESPACE" rollout status "deploy/$app" --timeout="${UP_TIMEOUT}s" >/dev/null 2>&1 &
    pids+=("$!")
  done
  for i in "${!APPS[@]}"; do
    wait "${pids[$i]}" || failed+=("${APPS[$i]}")
  done
  if [ ${#failed[@]} -gt 0 ]; then
    echo "edt.sh: not ready after ${UP_TIMEOUT}s: ${failed[*]}" >&2
    kubectl -n "$EDT_NAMESPACE" get pods >&2 || true
    local pod
    for app in "${failed[@]}"; do
      # The NEWEST pod: during a rollout "deploy/<app>" can pick the old, healthy one, which hides the problem.
      pod="$(kubectl -n "$EDT_NAMESPACE" get pods -l "app=$app" --sort-by=.metadata.creationTimestamp -o name | tail -1)"
      echo "--- last log lines of $pod" >&2
      # A pod that keeps crashing may have no current log: fall back to its previous attempt.
      { kubectl -n "$EDT_NAMESPACE" logs "$pod" --tail=8 2>/dev/null \
          || kubectl -n "$EDT_NAMESPACE" logs "$pod" --previous --tail=8 2>&1; } | cut -c1-220 >&2 || true
    done
    return 1
  fi
  echo "All four applications are ready."
}

up_step() {
  check_prerequisites
  local dcc_existed="" edt_existed=""
  helm status dcc-tools -n "$DCC_NAMESPACE" >/dev/null 2>&1 && dcc_existed=yes
  helm status edt -n "$EDT_NAMESPACE" >/dev/null 2>&1 && edt_existed=yes

  build_images
  load_images
  install_dcc_chart
  secrets_step
  install_edt_chart

  # Pods read the certificate store and the DSP key at start. On a second run the secrets step has replaced them
  # (new test CA, new DSP key), so pods that were already running must start again to pick them up.
  if [ -n "$dcc_existed" ]; then
    kubectl -n "$DCC_NAMESPACE" rollout restart deploy/dsp-simulator >/dev/null
  fi
  if [ -n "$edt_existed" ]; then
    kubectl -n "$EDT_NAMESPACE" rollout restart "${APPS[@]/#/deploy/}" >/dev/null
  fi

  wait_apps_ready || exit 1
  echo "Done. Port-forward with: deploy/edt.sh forward"
}

# Removes only what this script created. The images stay in the node (they are replaced the next time "up" runs).
down_step() {
  echo "Removing the EDT applications and the emulated DCC side..."
  helm uninstall edt -n "$EDT_NAMESPACE" >/dev/null 2>&1 || true
  helm uninstall dcc-tools -n "$DCC_NAMESPACE" >/dev/null 2>&1 || true
  # The Secrets made by the secrets step are not part of a release, so deleting the namespaces removes them too.
  kubectl delete namespace "$EDT_NAMESPACE" "$DCC_NAMESPACE" --ignore-not-found --wait=true
}

# The same local ports as the Docker Compose setup, so existing notes and bookmarks still work.
forward_step() {
  local pids=()
  trap 'kill "${pids[@]}" 2>/dev/null || true' EXIT INT TERM
  forward() {   # forward <namespace> <service> <local port> <service port> <what it is>
    kubectl -n "$1" port-forward "svc/$2" "$3:$4" >/dev/null 2>&1 &
    pids+=("$!")
    printf '  http://localhost:%-5s %s\n' "$3" "$5"
  }
  echo "Forwarding (Ctrl+C stops all of them):"
  forward "$DCC_NAMESPACE" temporal-ui 8088 8088 "Temporal UI"
  forward "$DCC_NAMESPACE" floci 4566 4566 "Floci (AWS endpoint)"
  forward "$EDT_NAMESPACE" batch-ingest 8081 8081 "Batch Ingest (/q/health/ready)"
  forward "$EDT_NAMESPACE" orchestrator 8082 8082 "Orchestrator (/q/health/ready)"
  forward "$EDT_NAMESPACE" duis-signer 8083 8083 "DUIS Signer (/q/health/ready)"
  forward "$EDT_NAMESPACE" response-listener 8084 8084 "Response Listener (/q/health/ready)"
  wait
}

[ $# -eq 1 ] || usage
require_context

case "$1" in
  up) up_step ;;
  down) down_step ;;
  forward) forward_step ;;
  secrets) secrets_step ;;
  *) usage ;;
esac
