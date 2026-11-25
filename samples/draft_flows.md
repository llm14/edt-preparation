# Draft Flows

Two draft Flows, written from `possible_flows.csv`. **Nothing here is confirmed by DCC.** "Inferred" means my reading of the
file, not something it states. DCC decides the 3 approved Flows, their order, the decision after each Response and the
Service Request allowlist per User Role.

## Flow A: communications hub triage

Target: CHF

| Step | SRV | Request | Future dated | Run when | Fact or decision | Basis |
|---|---|---|---|---|---|---|
| 1 | 8.9 | Read Device Log | DSP | always | last communication per device, signal strength, CBKE completed | stated in the file |
| 2 | 6.30 | Read CHF Sub GHz Configuration | No | after 8.9 | was a Zigbee channel changed? Compare against the last communication from step 1 | stated: "correlated against the last communication timestamp from an SRV 8.9" |
| 3 | 6.31 | Read CHF Sub GHz Channel | No | after 6.30 | current channel | inferred (no note in the file) |
| 4 | 6.32 | Read CHF Sub GHz Channel Log | No | after 6.31 | channel history | inferred (no note in the file) |
| optional | 6.13 | Read Event Or Security Log | No | last | errors and failures on the hub | stated, but "to be used sparingly as response could be large" |

## Flow B: meter triage

Targets: ESME, GSME, GPF (the GPF is not a device type we handle yet)

| Step | SRV | Request | Future dated | Run when | Fact or decision | Basis |
|---|---|---|---|---|---|---|
| 1 | 6.2.4 **or** 6.2.9 | Read Device Configuration (identity, or payment mode) | No | always | end-to-end connectivity (SMETS1); 6.2.9 also shows SMI misalignment | stated. Which of the two is **open** |
| 2 | 11.2 | Read Firmware Version | DSP | after step 1 | on the HAN but not answering GBCS messages, or really unreachable | stated, but its stated condition is an 8.9 showing a recent last communication (open point 1) |
| 3 | 7.4 | Read Supply Status | No | GSME only | battery level, for when the low-battery alert was lost | stated |
| optional | 6.13 | Read Event Or Security Log | No | last | errors and failures | stated, same warning about size |

## Not in either Flow

- **8.2 Read Inventory and 12.1 Request WAN Matrix:** both have target "N/A". They are DCC data lookups, not device requests.
- **A third Flow:** the file does not support one. The only candidate is to split Flow A into 8.9 and a separate 6.30 to 6.32
  Flow, but the file gives no reason to.

## Open points before building

| # | Question | Why it matters |
|---|---|---|
| 1 | Does the 11.2 condition ("following an 8.9 that shows a recent last communication") mean Flow B first sends an 8.9 to the meter's hub? | The 8.9 goes to a hub and the 11.2 to a meter, so the chain spans two devices. Our manifest has no hub ID for a meter. |
| 2 | 6.2.4 or 6.2.9 in Flow B? Or both? | Their notes overlap, and they differ only in what they read (identity or payment mode). |
| 3 | When does a Flow stop, and which Verdict does each outcome give? | The file has no decision rules apart from the 11.2 condition. |
| 4 | Is 6.13 part of a Flow at all, or only run on demand? | The file warns about large responses. |
| 5 | Do 8.9 and 11.2 run future dated, as scheduled reports? | The file says "DSP future dated" for them, and we do not support that yet. |

## Also missing in the code (for when the Flows are real)

- The Response decoder knows only 4.1.1 and 8.9. Each other SRV needs its own Response reader.
- Device types GPF, HCALCS and PPMID are not handled.
- There is no future dating in the Service Request.
