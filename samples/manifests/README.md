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
