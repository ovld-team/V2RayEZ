# Neighbor conflict map (human)

| Touched | Must also pass |
|---------|----------------|
| F1 settings | DataStore coerce; cold start |
| S1 split | Full-tunnel; empty allow; Tor routeAll |
| T1a/T1b Tor | Xray path; VPN abort; no orphan |
| H2 Always-on | H3 boot; no fake Always-on state |
| H3 boot/battery | F1 auto flags; honest notifs |
| H1 Hotspot | allowLan; process-core warn; S1 |
| W1–W4 downloads | cancel/timeout; resolvers; no fat assets |
| W6 strip | missing CTA; hev+AAR present |
| U1/U2 motion | reduce-motion; no Lottie |
| O12/O34 wizard | F1 wants; terms→Crashlytics |
| FB1 Firebase | assemble; consent off; no PII |
| B1/B2 Browser | MITM proxy; WebView destroy; tab kept |
| L1/D1 | tags + honest diagnostics |
| PG R8 | Hilt/JNI/Firebase keep |
| OPT | Tor kill + Xray + Browser + motion |

Run: `./scripts/gates/task-exit.sh <todo-id>`
