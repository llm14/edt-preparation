#!/usr/bin/env bash
# Brings the EDT up on the local Docker Desktop Kubernetes cluster, and removes it again.
#
#   deploy/edt.sh up        build images, install both charts, wait until the four apps are ready
#   deploy/edt.sh down      remove both charts and their namespaces
#   deploy/edt.sh forward   port-forward the Temporal UI, the app health ports and Floci
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
  echo "Usage: deploy/edt.sh {up|down|forward}" >&2
  exit 2
}

[ $# -eq 1 ] || usage
require_context

case "$1" in
  up | down | forward) die "'$1' is not implemented yet (task 5.1 in the local-kubernetes-helm change)" ;;
  *) usage ;;
esac
