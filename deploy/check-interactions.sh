#!/usr/bin/env bash
# Checks that each EDT app wrote a line for every interface of the architecture model that a one-device run uses
# (openspec/changes/improve-logging, design decision 2 and the table in it).
#
#   deploy/check-interactions.sh
#
# Reads "kubectl logs" of each app in the "edt" namespace. For each app it lists the "edt.interface" values the model says
# a one-device run uses on that container, and fails for each one that has no line in that app's log. A line counts when
# its log.logger is edt.interaction; the one exception is the DUIS Signer's KMS call (edt.duisSignerReqKms), which is the
# audit line on edt.audit (design decision 3: an audit event must not disappear when edt.interaction is turned down).
# Run it after a one-device Manifest has been processed (deploy/edt.sh up, then upload samples/manifests/one-device.csv).
# Exits 1 if an interface has no line, 2 if the logs cannot be read.
# Settings: EDT_NAMESPACE (default edt), EDT_LOG_TAIL (lines read per app, default 5000),
# EDT_LOG_FIXTURE_DIR (read <dir>/<app>.log for each app instead of the cluster; used to test this script).
set -euo pipefail

# Git Bash on Windows would rewrite arguments that look like Unix paths.
export MSYS_NO_PATHCONV=1

NAMESPACE="${EDT_NAMESPACE:-edt}"
TAIL="${EDT_LOG_TAIL:-5000}"

NAMESPACE="$NAMESPACE" TAIL="$TAIL" FIXTURE_DIR="${EDT_LOG_FIXTURE_DIR:-}" python - <<'PY'
import json, os, subprocess, sys

# The interfaces of the model that a one-device run uses, per container (design table, rows 1 to 17).
EXPECTED = {
    "batch-ingest": ["edt.batchIngestReqSqs", "edt.batchIngestReqS3", "edt.batchIngestReqKms", "edt.batchIngestReqGrpc"],
    "orchestrator": ["edt.orchestratorReqGrpc", "edt.orchestratorReqS3", "edt.orchestratorReqHttp",
                     "edt.orchestratorReqKafka", "edt.orchestratorReqFile"],
    "duis-signer": ["edt.duisSignerReqGrpc", "edt.duisSignerReqKms", "edt.duisSignerReqFile"],
    "response-listener": ["dcc.dspGatewayReqHttp", "edt.responseListenerReqFile"],
}
# Interfaces whose line is on edt.audit, not edt.interaction.
ON_AUDIT = {"edt.duisSignerReqKms"}

missing_total = 0
unreadable = False
for app, interfaces in EXPECTED.items():
    if os.environ["FIXTURE_DIR"]:
        path = os.path.join(os.environ["FIXTURE_DIR"], f"{app}.log")
        if not os.path.exists(path):
            print(f"{app}: no fixture file {path}")
            sys.exit(2)
        with open(path, encoding="utf-8") as f:
            text = f.read()
    else:
        run = subprocess.run(["kubectl", "-n", os.environ["NAMESPACE"], "logs", f"deployment/{app}", f"--tail={os.environ['TAIL']}"],
                             capture_output=True, text=True, encoding="utf-8", errors="replace")
        if run.returncode != 0:
            print(f"{app}: cannot read logs from namespace {os.environ['NAMESPACE']} (is the cluster up?): {run.stderr.strip()[:300]}")
            sys.exit(2)
        text = run.stdout

    seen = set()
    for line in text.splitlines():
        try:
            obj = json.loads(line)
        except ValueError:
            continue
        if not isinstance(obj, dict):
            continue
        interface = obj.get("edt.interface")
        logger = obj.get("log.logger")
        if interface and (logger == "edt.interaction" or (logger == "edt.audit" and interface in ON_AUDIT)):
            seen.add(interface)

    missing = [i for i in interfaces if i not in seen]
    if missing:
        missing_total += len(missing)
        for interface in missing:
            where = "edt.audit" if interface in ON_AUDIT else "edt.interaction"
            print(f"{app}: MISSING {interface} (no line on {where})")
    else:
        print(f"{app}: all {len(interfaces)} interfaces have a line -> ok")

if missing_total:
    sys.exit(1)
print("all containers ok")
PY
