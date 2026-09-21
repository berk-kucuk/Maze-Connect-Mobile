# Maze Connect threat model (mobile)

Status: design outline, mirrors `Maze-Connect/docs/THREAT_MODEL.md` for
protocol-level threats. This is the checklist every capability feature and
service must be reviewed against before it ships.

| Threat | Mitigation |
|---|---|
| MITM during pairing | public-key-bound Short Authentication String, mandatory human comparison (see "Design decisions" below) |
| MITM/rogue-AP after pairing | P-256 public-key pinning via a custom `TrustManager`, hard-fail on mismatch |
| Replay | monotonic per-session counters + replay window on top of TLS 1.3 |
| Malicious/oversized payloads | per-message-type frame caps, reject before full buffering |
| Path traversal / zip-slip in file transfer | reject `..`/separators/control chars, confine writes under SAF-scoped storage, never follow symlinks |
| DoS from a rogue LAN peer | rate-limited pairing/connection endpoints, handshake timeouts |
| Downgrade | TLS 1.3 hard-pinned min=max, `cleartextTrafficPermitted="false"` with no exceptions (`res/xml/network_security_config.xml`) |
| Key storage | P-256 keypair generated *inside* the Android Keystore and non-exportable; hardware-backed (TEE/StrongBox) where the device supports it, so the private key never enters app memory |
| Sensitive-op gating | `BiometricPrompt` gates view/delete pairing, re-pair, and wipe — not per-message, that would make the app unusable. **Not yet implemented.** |
| Manifest permission minimization | only `INTERNET`, `ACCESS_WIFI_STATE`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE(+CONNECTED_DEVICE)`; `BIND_NOTIFICATION_LISTENER_SERVICE` is **not** declared and will not be — notification mirroring was removed, which is also what lifted Play Protect's sideload block |
| Outbound internet surface | exactly one: a GET of `latest.json` over https from the update reminder. No query string, no cookies, no device identifier. The one header set is a fixed `User-Agent: MazeConnect/<version> (Android)`, which replaces a platform default that would otherwise have carried the phone's model and build id. The reply is treated as untrusted input (capped body, bounded fields, redirects not followed, download link accepted only on the manifest's own host) and **is never acted on**: the app does not download or install anything, so a compromised manifest can misreport a version number and nothing else. `cleartextTrafficPermitted="false"` stays absolute — an http endpoint here would be refused by the platform, which is intended. Switchable off in Settings. |
| File access | Storage Access Framework, never a broad storage permission |
| Rendering a computer's snapshot | the capability defaults **off** and is enabled per device; a report is only accepted from a computer this app asked; and every field is bounded in `SystemStatus.parse` before display — lengths capped, control characters refused (an escape sequence in a label must not be able to forge rows or move a cursor), row counts limited, percentages clamped. A field that fails is dropped, never defaulted into something that reads as a real measurement |
| Feature surface | clipboard sync and notification mirroring were **removed** rather than kept behind switches; `SecretDetector` went with them |

Shared protocol-level threats (MITM, replay, downgrade, framing caps) apply
identically on both clients and must stay consistent with
`Maze-Connect/docs/THREAT_MODEL.md`.


## Design decisions that differ from the original plan

Both were forced by what the platforms actually expose, and both were
verified rather than assumed.

### The SAS is bound to public keys, not the TLS exporter secret

The plan called for binding the pairing code to the RFC 5705
`tls-exporter` secret. Neither Qt's `QSslSocket` nor Android's
`javax.net.ssl` exposes `SSL_export_keying_material`, so that binding is
unreachable without replacing the whole TLS stack on both clients.

Binding to both peers' long-term public keys preserves the property that
matters. A man-in-the-middle cannot forge either key, so it must terminate
TLS twice and present its own key to each side:

    Alice sees (A, M)  ->  code_A = H(A, M, nonces)
    Bob   sees (M, B)  ->  code_B = H(M, B, nonces)

The two codes differ, the on-screen comparison fails, and pairing aborts.
This is the same construction KDE Connect and Signal safety numbers use.
Covered by `TestCrypto::sasDetectsManInTheMiddle` and
`SasTest.detectsManInTheMiddle`.

**That argument is only true with the commitment round, and for a while it
was not there.** The nonce exchange used to be one round — the initiator
sent its nonce, the responder answered with its own — which let the
man-in-the-middle above choose its nonce *after* seeing the other side's.
It would complete the Bob half first, fixing `code_B`, then search its own
nonce until `code_A` came out equal: a 10^6 space, well under a second,
after which both users see the same six digits and confirm. Key binding
does not prevent this, because Mallory is not forging a key — it is
steering the only input it controls.

Pairing is therefore three messages, with the initiator committing to its
nonce before the responder picks one (see docs/PROTOCOL.md §Pairing).
Neither half of a man-in-the-middle can move its contribution after
learning the other's. `TestCrypto::commitmentStopsAGrindingManInTheMiddle`
and `SasTest.commitmentStopsAGrindingManInTheMiddle` perform the grind for
real and then assert the commitment refuses the result — the passive-relay
tests above would have stayed green throughout the weakness, which is why
they are not enough on their own.

### Device identity is EC P-256, not Ed25519

Three findings changed this, and the second is the security-relevant one:

1. Qt 6's `QSslKey` has no Ed25519 algorithm (`QSsl::KeyAlgorithm` is
   Rsa/Dsa/Ec/Dh/MlDsa), so an Ed25519 key cannot be handed to
   `QSslSocket` without an opaque-handle escape hatch.
2. Android's Keystore hardware-backs EC P-256 on essentially every
   shipping device; Ed25519 is not hardware-backed. Choosing P-256 is what
   actually lets the mobile private key live in the TEE/StrongBox and never
   enter app memory — worth far more here than any difference in the
   curves' own margins.
3. SubjectPublicKeyInfo DER is byte-identical between OpenSSL's
   `i2d_PUBKEY()` and Java's `PublicKey.getEncoded()`, so both clients
   derive identical fingerprints and identical pairing codes with no custom
   encoding to keep in sync.

The cross-platform agreement is pinned by a known-answer test asserted on
both sides: `TestCrypto::matchesCrossPlatformKnownAnswer` and
`SasTest.matchesKnownAnswerFromSharedConstruction` both require the code
`876154` for the same fixed input. If either derivation drifts, those fail
rather than the two clients silently failing to pair in the field.
