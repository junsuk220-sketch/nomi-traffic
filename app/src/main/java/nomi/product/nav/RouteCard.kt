package nomi.product.nav

/**
 * A transit trip the user actually started in an external map app.
 * Not a search preview. Does not drive [NavigationSession] or TTS.
 */
enum class RouteCardMode {
    TRANSIT,
}

data class RouteCard(
    val provider: NavigationEventSource,
    val destinationName: String,
    val mode: RouteCardMode,
    val lastUsedAtMillis: Long,
) {
    fun key(): String = keyOf(provider, destinationName, mode)

    companion object {
        fun keyOf(
            provider: NavigationEventSource,
            destinationName: String,
            mode: RouteCardMode,
        ): String = "${provider.name}|${destinationName.trim()}|${mode.name}"
    }
}
