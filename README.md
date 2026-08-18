# CS15iP IR Remote

Android IR-blaster remote for the **Sony ICF-CS15iP** personal audio docking system
(original remote: RMT-CCS15iP).

## Why this app exists

The IR codes for this unit are **not published anywhere** — not in RemoteCentral, the
JP1 forums, LIRC, irdb, Flipper-IRDB, or Global Caché. The protocol is known to be
**Sony SIRC (40 kHz)**; only the device address is unknown. This app both *controls*
the unit (once the code is known) and *discovers* the code using the phone itself.

## How to use

1. Install the APK (grab `app-debug.apk` from the **Releases** page — built
   automatically by GitHub Actions on every push).
2. Needs a phone with an IR blaster (ConsumerIrManager).
3. Try the four candidate code sets from the dropdown — press **Power** in each.
   They come from sibling Sony products:
   - Sony15 device 68 — Sony clock-radio remotes (RMT-C350 / RMT-CS200A)
   - Sony15 device 100 — Sony boombox transport/tuner
   - Sony15 device 153 — Sony SRS-GU10iP iPod dock speaker
   - Sony20 device 26 ext 19 — Sony ICF-CDK50 / XDR dock radios
4. If none respond: run **Sweep cmds 0–127** on each candidate, then
   **Brute all addresses** (probes Power/Vol+ across every SIRC15 and SIRC12
   address, ~4 minutes). Point the phone at the radio; press **STOP** the moment
   it reacts. The address/cmd on screen is the real device code.
5. Found the code? Please open an issue/PR here and submit it to
   [Flipper-IRDB](https://github.com/Lucaslhm/Flipper-IRDB) and
   [irdb](https://github.com/probonopd/irdb) — you'd be the first to publish it.

## SIRC timing used

2400 µs header, 1200 µs = 1, 600 µs = 0, 600 µs spaces, LSB-first,
7 command bits + 5/8 address bits (+8 extended for SIRC20), 45 ms frame period,
3 repeats per press.
