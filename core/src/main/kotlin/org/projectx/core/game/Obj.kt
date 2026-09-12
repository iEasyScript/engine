package org.projectx.core.game

import kotlinx.serialization.Serializable
import org.projectx.core.attribute.Attributes
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.ObjType
import world.gregs.voidps.gameval.Gameval

@Serializable
class Obj(
    val id: Int,
    var amount: Int = 1,
    val attributes: Attributes? = null,
) {
    constructor(name: String, amount: Int = 1) : this(Gameval.requireId("obj", name), amount)

    val def: ObjType get() = Cache.obj(id) ?: ObjType.EMPTY
    val name: String get() = def.name
    val stackable: Boolean get() = def.isStackable() || def.noted
    val noted: Boolean get() = def.noted
    val notedId: Int get() = def.notedId
    val price: Long get() = def.cost
    val equipmentSlot: Int get() = def.wearPos

    val hasMetadata: Boolean get() = attributes != null && !attributes.isEmpty

    fun stacksWith(other: Obj): Boolean = id == other.id && !hasMetadata && !other.hasMetadata

    fun copy(id: Int = this.id, amount: Int = this.amount): Obj = Obj(id, amount, attributes?.copy())

    fun withAmount(amount: Int): Obj = Obj(id, amount, attributes)

    fun withVar(name: String, value: Any): Obj =
        Obj(id, amount, (attributes?.copy() ?: Attributes()).apply { this[name] = value })

    override fun equals(other: Any?): Boolean =
        other is Obj && other.id == id && other.amount == amount && other.attributes == attributes

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + amount
        result = 31 * result + (attributes?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "Obj($name x$amount)"
}
