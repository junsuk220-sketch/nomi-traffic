package nomi.product.nav

interface RouteCardStore {
    fun loadAll(): List<RouteCard>
    fun upsert(card: RouteCard)
    fun find(
        provider: NavigationEventSource,
        destinationName: String,
        mode: RouteCardMode,
    ): RouteCard?
}

class InMemoryRouteCardStore(
    initial: List<RouteCard> = emptyList(),
) : RouteCardStore {
    private val items = initial.associateBy { it.key() }.toMutableMap()

    override fun loadAll(): List<RouteCard> = items.values.toList()

    override fun upsert(card: RouteCard) {
        items[card.key()] = card
    }

    override fun find(
        provider: NavigationEventSource,
        destinationName: String,
        mode: RouteCardMode,
    ): RouteCard? = items[RouteCard.keyOf(provider, destinationName, mode)]
}
