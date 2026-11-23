# Sample Manifests

Placeholder format: CSV with a header, one device per row. **[CONFIRM the columns with DCC]**

| Column | Meaning |
|---|---|
| `device_id` | the device to diagnose; its last group picks the DSP simulator's behaviour (A0 responds, A1 N12, ...) |
| `device_type` | ESME, GSME, CHF, ... |
| `flow` | which of the 3 approved Flows to run (placeholder names) |

Upload one, signed by DCC's Manifest key (from `dcc-tools/`, with dcc-tools running):

    docker compose run --rm manifest-upload /manifests/sample.csv
    docker compose run --rm manifest-upload /manifests/sample.csv --bad-signature

## Files

- `one-device.csv`: one device that responds (A0). The happy path.
- `three-devices.csv`: A0 responds, A1 gets a DCC Alert N12, A5 gets N53 and then a Response.
- `three-types.csv`: one device of each type, all answering (A0): an ESME (4.1.1, electricity), a GSME (4.1.1, gas) and a CHF (8.9, device log).
  The simulator answers a 4.1.1 request with a gas reading only for the devices in its `EDT_GAS_DEVICES` setting, so
  `EE-FF-01-02-03-04-07-A0` is listed there by default (`dcc-tools/docker-compose.yml`, `deploy/helm/dcc-tools/values.yaml`).
