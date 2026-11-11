Inputs for the `ServiceRequest` workflow (PLACEHOLDER data). The last group of `targetDevice` tells the
DSP simulator what to do (see `dcc-tools/dsp-simulator/.../DeviceBehaviour.java`).
The workflow ID must be `service-request-` + `requestId`, as the Flow will do.

| File | DSP simulator does | Outcome without a Response Listener (12b) | With it (12c, verified) |
|---|---|---|---|
| `A0-respond.json` | accept, Response after 5 s | TIMED_OUT | RESPONDED |
| `A1-n12.json` | accept, DCC Alert N12 | TIMED_OUT | FAILED_ALERT |
| `A2-n13.json` | accept, DCC Alert N13 | TIMED_OUT | FAILED_ALERT |
| `A3-silent.json` | accept, nothing | TIMED_OUT | TIMED_OUT |
| `A4-rejected.json` | reject at once | REJECTED_BY_DSP | REJECTED_BY_DSP |
| `A5-n53-then-respond.json` | accept, N53, then Response | TIMED_OUT | RESPONDED |
