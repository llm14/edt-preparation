# Sample Manifests

Placeholder format: CSV with a header, one device per row. **[CONFIRM the columns with DCC]**

| Column | Meaning |
|---|---|
| `device_id` | the device to diagnose, in the EUI-64 form of the RTDS examples. Its last group picks the DSP simulator's behaviour (A0 responds, A1 N12, ...), see `device-behaviours.csv` |
| `device_type` | ESME, GSME, CHF, ... The Orchestrator sends SRV 8.9 to a CHF and 4.1.1 to the others |
| `flow` | which of the 3 approved Flows to run (placeholder names; not read yet, the Orchestrator picks the Service Request from the device type) |

## Which file for what

| File | Purpose | Devices | Takes about |
|---|---|---|---|
| `one-device.csv` | **The happy path.** One device that responds. Use it first, and for the checks in `deploy/` (`check-interactions.sh`, `check-log-shape.sh`) | `...-90-A0` ESME | seconds |
| `device-types.csv` | **One device of each type**, all answering: an ESME (4.1.1, electricity), a GSME (4.1.1, gas) and a CHF (8.9, device log). Shows the three Response shapes and their Facts | `...-92-A0` ESME, `...-93-A0` GSME, `...-94-A0` CHF | seconds |
| `device-behaviours.csv` | **One device per behaviour of the DSP simulator**, A0 to A7, all ESME. Shows each verdict and failure path | `...-91-A0` to `...-91-A7` | about 4 minutes (A3 and A6 wait for the response timeout) |

Expected for `device-behaviours.csv` (run on the local cluster; the Batch result is `devices` 8, `pages` 5, verdicts `RESPONDS` 2, `NO_RESPONSE` 4, `NOT_DIAGNOSED` 2):

| Device | What the simulator does | Service Request | Verdict |
|---|---|---|---|
| A0 | accepts, then a Response | RESPONDED | RESPONDS |
| A1 | accepts, then DCC Alert N12 | FAILED_ALERT | NO_RESPONSE |
| A2 | accepts, then DCC Alert N13 | FAILED_ALERT | NO_RESPONSE |
| A3 | accepts, then nothing | TIMED_OUT | NO_RESPONSE |
| A4 | rejects in the synchronous response | REJECTED_BY_DSP | NOT_DIAGNOSED |
| A5 | accepts, then N53, then a Response | RESPONDED | RESPONDS |
| A6 | accepts, then a Response changed after signing (discarded) | TIMED_OUT | NO_RESPONSE |
| A7 | a synchronous response changed after signing (refused) | SEND_FAILED | NOT_DIAGNOSED |

### A GSME needs the gas setting

A real DUIS request carries no device type, so the DSP simulator cannot see the `device_type` column. It answers a 4.1.1
request with a **gas** reading only for the device IDs in its `EDT_GAS_DEVICES` setting, and with an electricity reading for
any other device. `device-types.csv` works because `00-DB-12-34-56-78-93-A0` is listed there by default
(`dcc-tools/docker-compose.yml`, `deploy/helm/dcc-tools/values.yaml`). A GSME that responds in another file must be listed too.

### Device IDs

The byte before the last group is different per file (`90` one-device, `91` device-behaviours, `92` to `94` device-types), so no
device appears in two files.

## Uploading

Signed by DCC's Manifest key, from `dcc-tools/` with dcc-tools running:

    docker compose run --rm manifest-upload /manifests/device-behaviours.csv
    docker compose run --rm manifest-upload /manifests/device-behaviours.csv --bad-signature

On the local Kubernetes cluster, see `HAPPY-PATH-MANUAL.md` (upload through a port-forward).
