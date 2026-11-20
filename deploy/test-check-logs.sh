#!/usr/bin/env bash
# Tests deploy/check-log-shape.sh and deploy/check-interactions.sh against made-up log files (no cluster needed).
#
#   deploy/test-check-logs.sh
#
# Writes <app>.log files in a temporary directory, points EDT_LOG_FIXTURE_DIR at it, and checks each script's exit code
# and that its output names the key, the container and the interface it complains about. Exits 1 if any check fails.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIR="$(mktemp -d)"
trap 'rm -rf "$DIR"' EXIT
# Git Bash on Windows: the scripts run a Windows python, which needs a Windows path.
PYDIR="$DIR"; command -v cygpath >/dev/null 2>&1 && PYDIR="$(cygpath -m "$DIR")"
FAILED=0

line() { # logger, then extra JSON members (already formatted, may be empty)
    local logger="$1" extra="${2:-}"
    printf '{"@timestamp":"2026-11-20T10:00:00Z","log.level":"INFO","message":"m","service.name":"x","ecs.version":"1.2.0","log.logger":"%s"%s}\n' \
        "$logger" "${extra:+,$extra}"
}
interaction() { # edt.interface
    line edt.interaction "\"event.action\":\"a\",\"event.outcome\":\"success\",\"edt.interface\":\"$1\",\"network.direction\":\"outbound\""
}
complete_logs() { # fills $DIR with a log for each app that has every interface
    : >"$DIR/batch-ingest.log"; : >"$DIR/orchestrator.log"; : >"$DIR/duis-signer.log"; : >"$DIR/response-listener.log"
    for i in edt.batchIngestReqSqs edt.batchIngestReqS3 edt.batchIngestReqKms edt.batchIngestReqGrpc; do interaction $i >>"$DIR/batch-ingest.log"; done
    for i in edt.orchestratorReqGrpc edt.orchestratorReqS3 edt.orchestratorReqHttp edt.orchestratorReqKafka edt.orchestratorReqFile; do interaction $i >>"$DIR/orchestrator.log"; done
    for i in edt.duisSignerReqGrpc edt.duisSignerReqFile; do interaction $i >>"$DIR/duis-signer.log"; done
    line edt.audit '"edt.interface":"edt.duisSignerReqKms"' >>"$DIR/duis-signer.log"
    for i in dcc.dspGatewayReqHttp edt.responseListenerReqFile; do interaction $i >>"$DIR/response-listener.log"; done
}

check() { # description, expected exit code, expected text in output (or empty), command...
    local description="$1" expected="$2" text="$3"; shift 3
    local output code
    output="$(EDT_LOG_FIXTURE_DIR="$PYDIR" "$@" 2>&1)"; code=$?
    if [ "$code" -ne "$expected" ] || { [ -n "$text" ] && ! grep -qF -- "$text" <<<"$output"; }; then
        echo "FAIL: $description (exit $code, wanted $expected${text:+, output containing: $text})"
        echo "$output" | sed 's/^/    /'
        FAILED=1
    else
        echo "ok:   $description"
    fi
}

SHAPE="$HERE/check-log-shape.sh"
INTERACTIONS="$HERE/check-interactions.sh"

complete_logs
check "shape: complete logs pass" 0 "all apps ok" bash "$SHAPE"

line edt.interaction '"event.action":"a","event.outcome":"success","network.direction":"outbound"' >>"$DIR/orchestrator.log"
check "shape: an interaction line without edt.interface fails and names the key" 1 "edt.interface" bash "$SHAPE"

complete_logs
line edt.interaction '"event.action":"a","edt.interface":"x","network.direction":"outbound"' >>"$DIR/batch-ingest.log"
check "shape: an interaction line without event.outcome fails and names the key" 1 "event.outcome" bash "$SHAPE"

complete_logs
line some.other.logger >>"$DIR/duis-signer.log"
check "shape: a line on another logger needs none of the interaction keys" 0 "all apps ok" bash "$SHAPE"

complete_logs
check "interactions: every interface present passes" 0 "all containers ok" bash "$INTERACTIONS"

complete_logs
grep -v "edt.orchestratorReqKafka" "$DIR/orchestrator.log" >"$DIR/o.tmp"; mv "$DIR/o.tmp" "$DIR/orchestrator.log"
check "interactions: a missing interface fails and names the container and interface" 1 "orchestrator: MISSING edt.orchestratorReqKafka" bash "$INTERACTIONS"

complete_logs
grep -v "edt.duisSignerReqKms" "$DIR/duis-signer.log" >"$DIR/d.tmp"; mv "$DIR/d.tmp" "$DIR/duis-signer.log"
check "interactions: the Signer's KMS call is missing without its audit line" 1 "duis-signer: MISSING edt.duisSignerReqKms" bash "$INTERACTIONS"

complete_logs
line edt.interaction '"event.action":"a","event.outcome":"success","edt.interface":"edt.duisSignerReqKms","network.direction":"outbound"' >"$DIR/x"
grep -v "edt.duisSignerReqKms" "$DIR/duis-signer.log" >"$DIR/d.tmp"; cat "$DIR/x" >>"$DIR/d.tmp"; mv "$DIR/d.tmp" "$DIR/duis-signer.log"
check "interactions: a Signer KMS line on edt.interaction counts too" 0 "all containers ok" bash "$INTERACTIONS"

[ "$FAILED" -eq 0 ] && echo "all checks passed"
exit "$FAILED"
