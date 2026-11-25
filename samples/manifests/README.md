# Sample Manifests

Placeholder format: CSV with a header, one device per row. **[CONFIRM the columns with DCC]**

| Column | Meaning |
|---|---|
| `device_id` | the device to diagnose, in the EUI-64 form of the RTDS examples. Its last group picks the DSP simulator's behaviour (A0 responds, A1 N12, ...), see `device-behaviours.csv` |
| `device_type` | ESME, GSME or CHF. The Flow accepts only some of them (below); the DUIS Signer checks the device type again for each SRV |
| `flow` | which Flow to run: `meter-triage` (ESME, GSME: SRV 6.2.9 then 11.2) or `hub-triage` (CHF: SRV 8.9 then 6.30). A name the catalogue does not have (for example the old `flow-1`), or a device type the Flow does not take, gives the row the verdict `NOT_DIAGNOSED` and sends nothing. Draft Flows, see `samples/draft_flows.md` |

## Which file for what

| File | Purpose | Devices | Takes about |
|---|---|---|---|
| `one-device.csv` | **The happy path.** One device that responds to both steps of `meter-triage`. Use it first, and for the checks in `deploy/` (`check-interactions.sh`, `check-log-shape.sh`) | `...-90-A0` ESME | seconds |
| `device-types.csv` | **One device of each type**, all answering: an ESME and a GSME (`meter-triage`: payment mode, then firmware version) and a CHF (`hub-triage`: device log, then Sub GHz configuration). Shows both Flows and the Facts of the four SRVs | `...-92-A0` ESME, `...-93-A0` GSME, `...-94-A0` CHF | seconds |
| `device-behaviours.csv` | **One device per behaviour of the DSP simulator**, A0 to A7, all ESME on `meter-triage`. Shows each verdict and failure path | `...-91-A0` to `...-91-A7` | about 4 minutes (A3 and A6 wait for the response timeout) |

Expected for `device-behaviours.csv` (run on the local cluster; the Batch result is `devices` 8, `pages` 5, verdicts `RESPONDS` 2, `NO_RESPONSE` 4, `NOT_DIAGNOSED` 2):

The simulator's behaviour is chosen by the device, so every step of the Flow gets the same behaviour. The Flow stops at the first
step that does not get a Response.

| Device | What the simulator does | Steps run (`FlowStep` events) | Verdict |
|---|---|---|---|
| A0 | accepts, then a Response | 2: RESPONDED, RESPONDED | RESPONDS |
| A1 | accepts, then DCC Alert N12 | 1: FAILED_ALERT | NO_RESPONSE |
| A2 | accepts, then DCC Alert N13 | 1: FAILED_ALERT | NO_RESPONSE |
| A3 | accepts, then nothing | 1: TIMED_OUT | NO_RESPONSE |
| A4 | rejects in the synchronous response | 1: REJECTED_BY_DSP | NOT_DIAGNOSED |
| A5 | accepts, then N53, then a Response | 2: RESPONDED, RESPONDED | RESPONDS |
| A6 | accepts, then a Response changed after signing (discarded) | 1: TIMED_OUT | NO_RESPONSE |
| A7 | a synchronous response changed after signing (refused) | 1: SEND_FAILED | NOT_DIAGNOSED |

That is 10 `FlowStep` events and 8 `DeviceResult` events for the file.

### The gas setting no longer matters here

The simulator answers SRV 4.1.1 with a **gas** reading only for the device IDs in its `EDT_GAS_DEVICES` setting
(`00-DB-12-34-56-78-93-A0` by default). The draft Flows do not send 4.1.1 (a GSME gets 6.2.9 and 11.2, which have no gas variant),
so the setting has no effect on these files. It stays for a Flow that sends 4.1.1 to a GSME.

### Device IDs

The byte before the last group is different per file (`90` one-device, `91` device-behaviours, `92` to `94` device-types), so no
device appears in two files.

## Uploading

Signed by DCC's Manifest key, from `dcc-tools/` with dcc-tools running:

    docker compose run --rm manifest-upload /manifests/device-behaviours.csv
    docker compose run --rm manifest-upload /manifests/device-behaviours.csv --bad-signature

On the local Kubernetes cluster, see `HAPPY-PATH-MANUAL.md` (upload through a port-forward).
