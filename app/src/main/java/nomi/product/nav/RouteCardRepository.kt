package nomi.product.nav

/**
 * Upserts recent transit cards by (provider, destination, mode).
 * Does not speak, geocode, or launch a map.
 */
class RouteCardRepository(
    private val store: RouteCardStore,
) {
    fun record(
        provider: NavigationEventSource,
        destinationName: String,
        mode: RouteCardMode,
        atMillis: Long,
    ): RouteCard? {
        val name = destinationName.trim()
        if (name.isEmpty()) return null
        val card = RouteCard(
            provider = provider,
            destinationName = name,
            mode = mode,
            lastUsedAtMillis = atMillis,
        )
        store.upsert(card)
        return card
    }

    fun recent(): List<RouteCard> =
        store.loadAll().sortedByDescending { it.lastUsedAtMillis }
}
