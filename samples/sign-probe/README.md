Inputs for the `SignProbe` workflow: a JSON string holding one unsigned Service Request in the
PLACEHOLDER format (not DUIS).

- `signed.json`: SRV 4.1.1, originator = Other User's User ID, device ESME. Expected: SIGNED as OTHER_USER.
- `refused.json`: SRV 9.9.9, on no allowlist. Expected: REFUSED.
