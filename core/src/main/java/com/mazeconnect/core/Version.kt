package com.mazeconnect.core

/**
 * Wire protocol version. Bumped on breaking changes to framing or to the
 * message set; must stay in lock-step with the desktop client's
 * `mazeconnect::core::kProtocolVersion`.
 *
 * v2 replaced the KDE-Connect-style capabilities (clipboard, notifications,
 * ping) with the Maze management ones. A v1 peer has nothing useful to say to
 * a v2 peer, and the version check refuses the link outright rather than
 * negotiating down.
 */
const val PROTOCOL_VERSION = 3
