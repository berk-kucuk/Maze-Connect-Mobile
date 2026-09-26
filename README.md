# Maze Connect Mobile

The **Android client** for Maze Connect — your phone and your
[Maze Linux](https://github.com/berk-kucuk/MazeLinux) computer, linked over
the LAN with verified, pinned mutual TLS.

The desktop counterpart lives at
[Maze-Connect](https://github.com/berk-kucuk/Maze-Connect).

## What it does

- **Home** — the computer's dashboard: CPU, memory, disk and temperatures with
  their detail, security services, network and hardening score. Also as a
  live status-bar reading (Now Bar on One UI) and as home-screen widgets.
- **Media** — every player on the computer (Spotify, browsers, VLC…): play,
  pause, skip, seek, player and system volume; with lock-screen controls.
- **Commands** — run entries from an allow-list defined *on the computer*;
  pinned ones on a home-screen widget.
- **Files** — both ways; from any app's Share menu; and **Clipboard**, which
  puts what you copied on the computer's clipboard. Sharing a link or text
  from any app sends it the same way.
- **More** — Devices (pair, reconnect), Guard (maze-guard killswitches, also
  as a widget), Maze AI chat through the computer's Ollama, Settings.
- **The computer sees this phone** — battery, storage, memory, network,
  ringer — and can **ring it** when it is lost: full volume even on silent,
  until you tap *Found it*. Both have an owner switch in Settings.

### Widgets

Five dashboard placements (2×1, 2×2, 4×1, 4×2 grid, 4×2 detailed), killswitch
controls and pinned commands. Each widget carries several layouts measured
for different sizes and shows the one that fits the space it is actually
given — so it is neither clipped on a short grid nor half empty on a tall one,
and it rearranges when rotated or resized (`WidgetSizing`).

## Status

The transport is done and verified against the real desktop client, with
golden vectors shared between the two so the wire formats cannot drift. Every
value a computer sends is bounded field by field before it reaches the screen
(`SystemStatus`, `MediaState`), and every capability is granted by pairing and
revocable per device on the computer. Not yet implemented: BiometricPrompt
gating on destructive actions.

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
