package dev.multisystem.client

internal object UiInputRules {
    fun filterSlot(value: String): String = value.filter {
        it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_'
    }.take(48)

    fun userdataBytes(value: String): Long? {
        val size = value.toDoubleOrNull() ?: return null
        if (!size.isFinite() || size !in 1.0..64.0) return null
        return ((size * 1073741824.0).toLong() / 512L) * 512L
    }
}
