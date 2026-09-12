package com.projectx.game.nxt.entity.location

import com.projectx.game.bootstrap.Bootstrap
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.LocType
import world.gregs.voidps.cache.type.data.VarDomain

/**
 * Multi-loc resolution: a LocType carrying a `transforms` array renders as one of its child ids,
 * selected by a keying varbit/varp, and a [HIDDEN] slot means it renders as nothing at all. The
 * client resolves this internally but leaves no flag on the scene entity, so the engine has to
 * repeat the lookup to tell a real loc from an unrendered transform placeholder.
 */
object LocTransform {
    const val HIDDEN = -1

    fun resolve(locId: Int): Int = runCatching {
        val type = Cache.loc(locId) ?: return locId
        val transforms = type.transforms
        if (transforms == null || transforms.isEmpty()) return locId
        transforms.slotFor(keyingValue(type) ?: return locId)
    }.getOrDefault(locId)

    /** Values outside the keyed slots — including the -1 of a loc with no keying var — take the
     *  trailing default slot. */
    private fun IntArray.slotFor(value: Int): Int = if (value in 0..size - 2) this[value] else last()

    /** Null when the keying var sits in a domain the engine cannot read, so the caller leaves the
     *  loc unresolved rather than hiding something the client is still drawing. */
    private fun keyingValue(type: LocType): Int? = when {
        type.varbit != -1 -> varbitValue(type.varbit)
        type.varp != -1 -> Bootstrap.client.playerVarDomain.getVar(type.varp)
        else -> -1
    }

    private fun varbitValue(id: Int): Int? = when (Cache.varbit(id)?.domain) {
        VarDomain.PLAYER -> Bootstrap.client.playerVarDomain.getVarBit(id)
        VarDomain.CLIENT -> Bootstrap.client.clientVarDomain.getVarBit(id)
        else -> null
    }
}
