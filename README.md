# Maze Connect Mobile

The **Android client** for Maze Connect — the phone half of managing
[Maze Linux](https://github.com/berk-kucuk/MazeLinux) remotely.

Not a KDE Connect clone. Clipboard sync, notification mirroring and
find-my-device were removed rather than reimplemented; KDE Connect already
does them well. What is left is what only this app can do: read the
computer's dashboard, run commands it has defined in advance, toggle
maze-guard's killswitches, and talk to Maze AI. File transfer stayed.

The wire protocol is custom (not KDE-Connect-compatible) and built around
mutual TLS 1.3, EC P-256 device identity and SAS-verified pairing. See
[`docs/PROTOCOL.md`](docs/PROTOCOL.md) and
[`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md) for the full design.

The desktop counterpart lives at
[Maze-Connect](https://github.com/berk-kucuk/Maze-Connect).

## Status

The transport is done and verified against the real desktop client: the
protocol core (framing, replay window, filename sanitisation, SAS
derivation), a hardware-backed Keystore identity, the pinned `TrustManager`,
mutual-TLS links, the pairing state machine, LAN discovery, pairing by typed
address, and the Compose UI. 45 unit tests, including golden vectors shared
with the desktop client so the two wire formats cannot silently drift apart.

**Verified end to end on an Android emulator:** paired with the desktop
client over real mutual TLS, both screens showed the identical code
`358329`, and both sides pinned the other's public key.

The dashboard (`systemStatus`) is code complete and awaiting a real-device
pass. Everything it displays arrives from the computer and is bounded field
by field in `SystemStatus.kt` before it reaches the screen — lengths capped,
control characters refused, row counts limited, percentages clamped. The link
is authenticated, which is not the same as the machine at the other end being
trustworthy.

Every management feature is a **capability that is off by default and enabled
per device**, on the Devices page. Pairing settles who a device is; it never
settles what it may be asked for. That holds for the dashboard too, which
only reads — "only reads" still covers the hostname, the local IP, the
kernel, the hardware and which security services are running.

Not yet implemented: receiving files (offers are refused rather than
written), and BiometricPrompt gating on destructive actions.

## Layout

| Path | Contents |
| --- | --- |
| `core/` | `com.mazeconnect.core` — protocol, crypto, pairing, transport, keystore. Android library module, unit-testable independent of Compose. |
| `app/` | `com.mazeconnect.app` — Compose UI and the foreground link service. |

## One APK

There used to be two flavours, and the reason is worth recording because it
governs anything that might reintroduce a notification listener.

Google Play Protect's enhanced fraud protection blocks **sideloading** of any
APK that declares a `NotificationListenerService`, because that is the
permission banking-fraud malware abuses. It cannot tell a legitimate use from
an abusive one, it does not ask, and it now covers 185 markets. The block
applies to "internet-sideloading sources" — a browser, a messaging app, a
file manager — and not to the Play Store install path, which is why KDE
Connect installs fine from Play while its F-Droid builds have been flagged.

Notification mirroring was removed with the pivot away from being a KDE
Connect clone, so the service is gone, the block no longer applies, and the
two flavours collapsed back to **one APK that sideloads normally**.

## Packaging

```sh
./gradlew assembleRelease   # -> dist/maze-connect-<version>.apk
./gradlew bundleRelease     # -> dist/maze-connect-<version>.aab
```

Release builds are signed from `keystore.properties`, which points at a
keystore under `keystore/`. Both are gitignored, and a clone without them
still builds — the release APK just comes out unsigned.

**The signing key is not recoverable.** Android will refuse to install a
build signed with a different key as an update over an existing one, so
losing `keystore/maze-connect-release.jks` or its password means every
existing install has to be removed and re-paired. Back both up somewhere
you control.

The APK is signed with APK Signature Scheme v3 only; `minSdk` is 28, and
every device that can install this understands v3. v3 is what allows the
key to be rotated later without orphaning installs.

## Installing on a phone

```sh
adb install dist/maze-connect-0.4.0.apk
```

Or copy the APK to the phone and open it — Android will ask permission to
install from that source once. Because it is self-signed rather than from a
store, the installer will say the app is from an unknown developer; that is
expected for a sideloaded build.

## Building

Requires JDK 17 and the Android SDK (`compileSdk`/`targetSdk` 37,
`minSdk` 28).

```sh
./gradlew :app:assembleDebug
./gradlew test
./gradlew lint
```
