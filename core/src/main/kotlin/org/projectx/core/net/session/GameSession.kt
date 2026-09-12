package org.projectx.core.net.session

import io.ktor.utils.io.*
import org.projectx.core.net.ClientPlatform
import org.projectx.core.net.Isaac
import org.projectx.core.net.Session
import org.projectx.core.net.prot.Codec

class GameSession(
    write: ByteWriteChannel,
    isaacIn: Isaac,
    isaacOut: Isaac,
    ip: String,
    codec: Codec,
    /** Protocol username of the logged-in player. Set after login. */
    var username: String = "",
) : Session(write, isaacIn, isaacOut, ip, codec) {
    /** The 128-bit login key (ISAAC seed), reused as the XTEA/tinyKey to decrypt C2S MESSAGE_PRIVATE. */
    var xteaKey: IntArray = IntArray(4)

    /**
     * Client platform decoded from the login descriptor's machine info. Drives the mobile-vs-desktop
     * interface/var/script set. [ClientPlatform.UNKNOWN] until the login handler decodes the block.
     */
    var platform: ClientPlatform = ClientPlatform.UNKNOWN

    /**
     * Opaque per-session attachment for the owning server to hang its player object on. The world
     * server stores its [org.projectx.world.entity.Player] here so handlers (which only receive the
     * [GameSession]) can reach per-player state. Core stays decoupled from world/lobby entity types;
     * consumers cast as needed (see `org.projectx.world.entity.player`).
     */
    var attachment: Any? = null
}
