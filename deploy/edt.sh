#!/usr/bin/env bash
# Brings the EDT up on the local Docker Desktop Kubernetes cluster, and removes it again.
#
#   deploy/edt.sh up        build images, install both charts, wait until the four apps are ready
#   deploy/edt.sh down      remove both charts and their namespaces
#   deploy/edt.sh forward   port-forward the Temporal UI, the app health ports and Floci
#   deploy/edt.sh secrets   (re)create the KMS keys, the bucket, the queue and the two Secrets (also part of "up")
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

[ $# -eq 1 ] || usage
require_context

case "$1" in
  secrets) secrets_step ;;
  up | down | forward) die "'$1' is not implemented yet (task 5.1 in the local-kubernetes-helm change)" ;;
  *) usage ;;
esac
