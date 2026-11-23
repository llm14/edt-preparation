Inputs for the `SignProbe` workflow. It takes **two inputs**:

1. a JSON string holding one unsigned DUIS 5.5 Service Request (the files here), and
2. the type of the target device as a JSON string, for example `'"ESME"'`: a DUIS request does not carry it, and the
   signer's policy checks it against the SRV.

The requests are written from the released examples (RTDS 4.1.1_ECS17b); the device in the Request ID is `...90-A0` (an
ESME) in `signed.json` and `...90-A2` (a Communications Hub) in `refused.json`.

- `signed.json` with `'"ESME"'`: SRV 4.1.1, originator = Other User's User ID. Expected: SIGNED as OTHER_USER.
- `refused.json` with `'"CHF"'`: the same SRV 4.1.1 for a device of type CHF, which the policy allows only 8.9 for.
  It passes the schema, so the refusal comes from the policy. Expected: REFUSED, "device type CHF is not allowed for SRV 4.1.1".

An invalid request (not DUIS, another namespace, an SRV the partial schema does not list) is refused by the schema check instead.
