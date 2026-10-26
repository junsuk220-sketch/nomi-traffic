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
        transferNote: String? = null,
        /** False on a new guidance: an older trip's note must not stay on the card. */
        keepNote: Boolean = true,
    ): RouteCard? {
        val name = destinationName.trim()
        if (name.isEmpty()) return null
        val existing = store.find(provider, name, mode)
        val given = transferNote?.trim()?.ifEmpty { null }
        val note = if (keepNote) given ?: existing?.transferNote else given
        val card = RouteCard(
            provider = provider,
            destinationName = name,
            mode = mode,
            lastUsedAtMillis = atMillis,
            transferNote = note,
        )
        store.upsert(card)
        return card
    }

    fun recent(): List<RouteCard> =
        store.loadAll().sortedByDescending { it.lastUsedAtMillis }
}
