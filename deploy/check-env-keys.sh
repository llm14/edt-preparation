#!/usr/bin/env bash
# Checks that each EDT app gets the same environment keys in the cluster as in Docker Compose.
#
#   deploy/check-env-keys.sh
#
# For every app it compares the keys the root docker-compose.yml sets (read with "docker compose config") with the keys
# in the app's ConfigMap rendered from the edt chart. It reports a key that is missing or extra, and a value that differs
# for any reason other than the service name: Compose reaches "temporal", "floci", "kafka" and "dsp-simulator" by their
# short names, the cluster by their full names in the "dcc-tools" namespace.
# Needs docker, helm, kubectl (only to turn rendered YAML into JSON, offline) and python. Exits 1 on any difference.
set -euo pipefail

# Git Bash on Windows would rewrite arguments that look like Unix paths.
export MSYS_NO_PATHCONV=1

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

COMPOSE_JSON="$(docker compose config --format json)"
# EDT_CHECK_HELM_ARGS adds helm arguments, for example --set to try a change before editing values.yaml.
# shellcheck disable=SC2086
CHART_JSON="$(helm template edt deploy/helm/edt -n edt ${EDT_CHECK_HELM_ARGS:-} | kubectl create --dry-run=client --validate=false -f - -o json)"

COMPOSE_JSON="$COMPOSE_JSON" CHART_JSON="$CHART_JSON" python - <<'PY'
import json, os, re, sys

# Compose reaches the emulated DCC side by short name; the cluster needs the full name (another namespace).
SERVICE_HOST = re.compile(r"(^|//)(temporal|floci|kafka|dsp-simulator)(?=:)")
def to_cluster_name(value):
    return SERVICE_HOST.sub(lambda m: f"{m.group(1)}{m.group(2)}.dcc-tools.svc.cluster.local", value)

def json_objects(text):
    """kubectl prints one JSON object per resource, not a list."""
    decoder, i, out = json.JSONDecoder(), 0, []
    while i < len(text):
        while i < len(text) and text[i].isspace():
            i += 1
        if i >= len(text):
            break
        obj, i = decoder.raw_decode(text, i)
        out.append(obj)
    return out

compose = json.loads(os.environ["COMPOSE_JSON"])["services"]
chart = {o["metadata"]["name"]: o.get("data") or {}   # an empty ConfigMap renders as "data": null
         for o in json_objects(os.environ["CHART_JSON"]) if o["kind"] == "ConfigMap"}

problems = 0
for app in sorted(compose):
    expected = compose[app].get("environment", {})
    actual = chart.get(app)
    if actual is None:
        print(f"{app}: no ConfigMap in the chart")
        problems += 1
        continue
    missing = sorted(set(expected) - set(actual))
    extra = sorted(set(actual) - set(expected))
    different = sorted(k for k in set(expected) & set(actual) if to_cluster_name(str(expected[k])) != str(actual[k]))
    status = "ok" if not (missing or extra or different) else "DIFFERENT"
    print(f"{app}: {len(expected)} keys in Compose, {len(actual)} in the chart -> {status}")
    for k in missing:
        print(f"    missing in chart: {k}")
    for k in extra:
        print(f"    extra in chart:   {k}")
    for k in different:
        print(f"    value differs:    {k}\n        Compose (as cluster name): {to_cluster_name(str(expected[k]))}\n        chart:                     {actual[k]}")
    problems += bool(missing or extra or different)

for app in sorted(set(chart) - set(compose)):
    print(f"{app}: in the chart but not in docker-compose.yml")
    problems += 1

sys.exit(1 if problems else 0)
PY
