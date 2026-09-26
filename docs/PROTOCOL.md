# Maze Connect wire protocol

Status: implemented. The mobile-side copy of the contract defined in
`Maze-Connect/docs/PROTOCOL.md` — the two must stay in lock-step (see
`com.mazeconnect.core.PROTOCOL_VERSION` / desktop's
`mazeconnect::core::kProtocolVersion`).

## Discovery

Unauthenticated, LAN-only, informational only. Periodic (~5s) UDP multicast
`announce` datagram on `239.255.83.10:38271` (subnet broadcast fallback if
multicast is filtered), via `MulticastSocket`/`NsdManager`:

```json
{ "protocolVersion": 2, "deviceIdHash": "...", "deviceName": "...", "deviceType": "desktop|mobile", "pairingPort": 0 }
```

Nothing from this beacon is trusted beyond populating a "nearby devices"
list.

## Transport

TCP + TLS 1.3 only, hard-pinned `min == max` version (no TLS 1.2 fallback,
ever). Mutual auth via self-signed X.509 certs wrapping each device's
long-term EC P-256 identity keypair, enforced through a custom `TrustManager`
(the pinned-fingerprint check — never rely on system CA trust for a paired
peer).

Payload confidentiality and integrity come from TLS 1.3 itself; there is
deliberately no second encryption layer inside it. See
`Maze-Connect/docs/PROTOCOL.md` for the reasoning.

## Pairing (TOFU + SAS)

Identical flow to the desktop client:
1. User selects a discovered device; a TLS 1.3 connection opens with the
   peer cert intentionally unverified, only inside the explicit pairing UI.
2. The nonce exchange is **committed** and takes three messages:

   ```
   initiator -> responder : PairRequest  { commitment = commit(N_i) }
   responder -> initiator : PairResponse { nonce = N_r }
   initiator -> responder : PairReveal   { nonce = N_i }
   ```

   `commit(n) = SHA-256("maze-connect/sas-commit/v1" || len‖n)`, its own
   context string so it cannot collide with the SAS hash over the same
   nonce. The responder verifies the opened nonce against the commitment in
   constant time and drops the link on a mismatch. Without this round a
   man-in-the-middle can fix one side's code and then grind its own nonce
   until the other side matches — 10^6 tries, under a second. See
   docs/THREAT_MODEL.md.
3. Both derive a 6-digit Short Authentication String over both public keys
   and nonces (length-prefixed, domain-separated). See
   docs/THREAT_MODEL.md for why this is not bound to the TLS exporter.
4. Both users confirm the codes match. Only then is the peer's fingerprint
   persisted via `AndroidKeyStoreManager`'s pinned-device store.
5. Later connections must match the pinned fingerprint exactly; on
   mismatch, hard-fail visibly — never silently re-prompt TOFU.

## Framing

4-byte big-endian length-prefixed frames, JSON control payloads with a
receiver-enforced max size, binary sub-channel for file-transfer chunks.
Per-session monotonic counters + replay window on top of TLS 1.3. Per-peer
rate limiting on pairing attempts and new connections.

## Capability negotiation

Same `hello` message, envelope and rules as the desktop client — see
`Maze-Connect/docs/PROTOCOL.md` for the full schema and the message tables.

Two sets that are easy to conflate, and are deliberately separate here:

* `Capability.SUPPORTED` — what this build implements, advertised in `hello`.
* `Capability.DEFAULT_ENABLED` — what a *newly paired* device starts with.

`systemStatus` is in the first and not the second. A capability only arms
when both peers advertise it and the local user has enabled it for that
specific device.

## Reading a statusReport

The snapshot is the one message that goes almost straight onto the screen,
and it comes from a machine that is authenticated rather than trustworthy.
`Message.unvalidatedObject("status")` is named to make that obvious; the
actual bounding happens in `SystemStatus.parse`, which caps string lengths,
refuses control characters, limits row counts and clamps percentages.

A field that fails is dropped rather than defaulted. A dashboard that invents
"0%" or "inactive" is worse than one that shows less, because the reader
cannot tell a quiet machine from a broken probe — which is also why `unknown`
is kept as a third state and never folded into `inactive`.

## Media (`media`)

The full contract is in `Maze-Connect/docs/PROTOCOL.md` § media. On this
side:

* `DeviceManager.setMediaInterest(device, token, wanted)` — the Media screen
  and the media notification each hold a token; the computer is subscribed
  (`mediaRequest` with `subscribe: true`) while any token is held, and
  re-subscribed in the next `hello` after a reconnect.
* A `mediaState` is dropped unless something here holds interest in that
  computer. `MediaState.parse` bounds every field before it reaches the
  screen or the lock screen (ids `[A-Za-z0-9_.-]{1,64}`, text cleaned and
  capped, volumes range-checked, at most 12 players).
* `sendMediaCommand` sends one `MediaAction`; the result comes back as the
  next `mediaState`.

## Scope

v2 dropped notification mirroring, clipboard sync and ping/find-my-device:
KDE Connect already does those. What remains is managing the computer itself,
file transfer, and — since 0.14.0 — the computer's media players. Deferred
indefinitely: battery sharing, remote input, SMS.
