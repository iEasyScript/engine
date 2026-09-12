package org.projectx.core.attribute

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface AttributeValue {
    @Serializable @SerialName("i") data class I(val v: Int) : AttributeValue
    @Serializable @SerialName("l") data class L(val v: Long) : AttributeValue
    @Serializable @SerialName("d") data class D(val v: Double) : AttributeValue
    @Serializable @SerialName("b") data class B(val v: Boolean) : AttributeValue
    @Serializable @SerialName("s") data class S(val v: String) : AttributeValue
}

@Serializable
class Attributes(private val values: MutableMap<String, AttributeValue> = LinkedHashMap()) {

    val isEmpty: Boolean get() = values.isEmpty()
    val keys: Set<String> get() = values.keys

    operator fun contains(key: String): Boolean = values.containsKey(key)

    operator fun set(key: String, value: Any) {
        values[key] = when (value) {
            is Int -> AttributeValue.I(value)
            is Long -> AttributeValue.L(value)
            is Double -> AttributeValue.D(value)
            is Boolean -> AttributeValue.B(value)
            is String -> AttributeValue.S(value)
            is Enum<*> -> AttributeValue.S(value.name)
            else -> throw IllegalArgumentException("Unsupported attribute type ${value::class.simpleName} for '$key'")
        }
    }

    operator fun get(key: String): Any? = when (val v = values[key]) {
        is AttributeValue.I -> v.v
        is AttributeValue.L -> v.v
        is AttributeValue.D -> v.v
        is AttributeValue.B -> v.v
        is AttributeValue.S -> v.v
        null -> null
    }

    fun getInt(key: String, default: Int = 0): Int = when (val v = values[key]) {
        is AttributeValue.I -> v.v
        is AttributeValue.L -> v.v.toInt()
        else -> default
    }

    fun getLong(key: String, default: Long = 0L): Long = when (val v = values[key]) {
        is AttributeValue.L -> v.v
        is AttributeValue.I -> v.v.toLong()
        else -> default
    }

    fun getDouble(key: String, default: Double = 0.0): Double = (values[key] as? AttributeValue.D)?.v ?: default
    fun getBoolean(key: String, default: Boolean = false): Boolean = (values[key] as? AttributeValue.B)?.v ?: default
    fun getString(key: String, default: String = ""): String = (values[key] as? AttributeValue.S)?.v ?: default

    fun remove(key: String): Boolean = values.remove(key) != null
    fun clear() = values.clear()
    fun copy(): Attributes = Attributes(LinkedHashMap(values))

    override fun equals(other: Any?): Boolean = other is Attributes && other.values == values
    override fun hashCode(): Int = values.hashCode()
    override fun toString(): String = values.toString()
}
