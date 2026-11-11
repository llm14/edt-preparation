#!/usr/bin/env bash
# Rate limit and fairness on the duis-send task queue (step 12d).
#
# Starts 40 Service Requests of Batch "big", then 2 of Batch "small", and prints when the DSP simulator
# received each one. Starting 40 takes the CLI about 14 s, so a backlog of "big" is waiting when "small" arrives.
# Expected: one per second in total (the send rate), and the two "small" ones sent soon after they arrive,
# not after all 40 "big" ones (fairness by Batch).
#
# Needs both stacks running (dcc-tools and the root compose). Run from the repository root:  bash samples/rate-limit/run.sh
set -euo pipefail

BIG=40
RUN=$(date +%s)          # makes the Request IDs unique for each run
SINCE=$(date -u +%Y-%m-%dT%H:%M:%SZ)

# One container starts every workflow (the image has sh, not bash).
docker run --rm --network edt -e RUN="$RUN" -e BIG="$BIG" --entrypoint sh temporalio/admin-tools:1.32.2 -c '
start() {   # $1 = Batch ID, $2 = number in this run
  id="90-B3-D5-1F-30-01-00-01:00-DB-12-34-56-78-90-A0:$RUN$2"
  temporal --address temporal:7233 --namespace edt workflow start \
    --type ServiceRequest --task-queue orchestrator --workflow-id "service-request-$id" \
    --input "{\"requestId\":\"$id\",\"batchId\":\"$1\",\"originator\":\"90-B3-D5-1F-30-01-00-01\",\"targetDevice\":\"00-DB-12-34-56-78-90-A0\",\"deviceType\":\"ESME\",\"srv\":\"4.1.1\"}" > /dev/null
}
for i in $(seq 101 $((100 + BIG))); do start big "$i"; done
echo "started $BIG of batch big at $(date -u +%T)"
for i in 901 902; do start small "$i"; done
echo "started 2 of batch small at $(date -u +%T)"
'

echo "waiting for the sends (1 per second)..."
sleep $((BIG - 10))

# Numbers 101.. are Batch "big", 901-902 Batch "small".
docker logs -t --since "$SINCE" dcc-tools-dsp-simulator-1 2>&1 \
  | grep "service-request-received" | grep ":$RUN" \
  | sed -E "s/^[0-9-]+T([0-9:]+)\.[0-9]+Z .*:$RUN([0-9]+) .*/\1 \2/" \
  | awk '{ batch = ($2 > 900) ? "small" : "big"; n++; print n ". " $1 "  #" $2 "  " batch }'
