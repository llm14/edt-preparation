#!/usr/bin/env bash
# Checks the shape of the real logs of the four EDT apps on the local cluster (openspec/specs/structured-logging).
#
#   deploy/check-log-shape.sh
#
# Reads "kubectl logs" of each app in the "edt" namespace and fails if a line:
#   - is not one JSON object,
#   - lacks @timestamp, log.level, message, service.name or ecs.version,
#   - has an "mdc" object (application fields must be top-level),
#   - repeats a key (found while parsing; a JSON parser would silently keep the last value),
#   - is on the logger edt.interaction and lacks event.action, event.outcome, edt.interface or network.direction.
# For each app it prints the number of lines read and, for each kind of problem, how many lines have it and the first one.
# Needs the cluster running (deploy/edt.sh up), kubectl and python. Exits 1 on any problem, 2 if there is nothing to read.
# Settings: EDT_NAMESPACE (default edt), EDT_LOG_TAIL (lines read per app, default 2000),
# EDT_LOG_FIXTURE_DIR (read <dir>/<app>.log for each app instead of the cluster; used to test this script).
set -euo pipefail

# Git Bash on Windows would rewrite arguments that look like Unix paths.
export MSYS_NO_PATHCONV=1

NAMESPACE="${EDT_NAMESPACE:-edt}"
TAIL="${EDT_LOG_TAIL:-2000}"
APPS="batch-ingest orchestrator duis-signer response-listener"

APPS="$APPS" NAMESPACE="$NAMESPACE" TAIL="$TAIL" FIXTURE_DIR="${EDT_LOG_FIXTURE_DIR:-}" python - <<'PY'
import json, os, subprocess, sys

REQUIRED = ["@timestamp", "log.level", "message", "service.name", "ecs.version"]
INTERACTION_LOGGER = "edt.interaction"
INTERACTION_KEYS = ["event.action", "event.outcome", "edt.interface", "network.direction"]

def no_duplicates(pairs):
    seen, dup = set(), []
    for key, _ in pairs:
        if key in seen:
            dup.append(key)
        seen.add(key)
    obj = dict(pairs)
    if dup:
        obj["\0duplicates"] = dup          # marker, read below
    return obj

def problems_of(line):
    """Returns a list of (kind, detail) for one log line."""
    try:
        obj = json.loads(line, object_pairs_hook=no_duplicates)
    except ValueError:
        return [("not JSON", "")]
    if not isinstance(obj, dict):
        return [("not a JSON object", "")]
    out = []
    missing = [k for k in REQUIRED if k not in obj]
    if missing:
        out.append(("missing keys", ", ".join(missing)))
    if obj.get("log.logger") == INTERACTION_LOGGER:
        absent = [k for k in INTERACTION_KEYS if k not in obj]
        if absent:
            out.append(("interaction line lacks keys", ", ".join(absent)))
    if "mdc" in obj:
        out.append(("has an mdc object", ", ".join(sorted(obj["mdc"])) if isinstance(obj["mdc"], dict) else ""))
    if "\0duplicates" in obj:
        out.append(("repeats a key", ", ".join(obj["\0duplicates"])))
    return out

total_problems = 0
empty = False
for app in os.environ["APPS"].split():
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
    lines = [l for l in text.splitlines() if l.strip()]
    if not lines:
        print(f"{app}: no log lines to check")
        empty = True
        continue
    found = {}                                  # kind -> [count, first line, detail]
    for line in lines:
        for kind, detail in problems_of(line):
            entry = found.setdefault(kind, [0, line, detail])
            entry[0] += 1
    if not found:
        print(f"{app}: {len(lines)} lines -> ok")
        continue
    total_problems += 1
    print(f"{app}: {len(lines)} lines -> PROBLEMS")
    for kind, (count, first, detail) in found.items():
        shown = first if len(first) <= 300 else first[:300] + "..."
        print(f"  {kind}: {count} line(s){' (' + detail + ')' if detail else ''}")
        print(f"    first: {shown}")

if total_problems:
    sys.exit(1)
if empty:
    sys.exit(2)
print("all apps ok")
PY
