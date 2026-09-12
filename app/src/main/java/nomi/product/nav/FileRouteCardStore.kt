package nomi.product.nav

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Local JSON store for recent transit cards.
 * Separate from Memory Engine and ConversationState.
 */
class FileRouteCardStore(
    private val file: Path,
) : RouteCardStore {
    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Any()

    override fun loadAll(): List<RouteCard> = synchronized(lock) {
        snapshot().cards.mapNotNull { it.toModel() }
    }

    override fun upsert(card: RouteCard) {
        synchronized(lock) {
            val next = snapshot().cards
                .filterNot { it.key == card.key() }
                .plus(PersistedRouteCard.from(card))
            write(PersistedRouteCards(next))
        }
    }

    override fun find(
        provider: NavigationEventSource,
        destinationName: String,
        mode: RouteCardMode,
    ): RouteCard? = synchronized(lock) {
        val key = RouteCard.keyOf(provider, destinationName, mode)
        snapshot().cards.firstOrNull { it.key == key }?.toModel()
    }

    private fun snapshot(): PersistedRouteCards {
        if (!Files.exists(file) || Files.size(file) == 0L) return PersistedRouteCards()
        return try {
            json.decodeFromString(
                PersistedRouteCards.serializer(),
                Files.readAllBytes(file).toString(Charsets.UTF_8),
            )
        } catch (_: Exception) {
            PersistedRouteCards()
        }
    }

    private fun write(payload: PersistedRouteCards) {
        val parent = file.parent
        if (parent != null) Files.createDirectories(parent)
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.write(tmp, json.encodeToString(PersistedRouteCards.serializer(), payload).toByteArray())
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

@Serializable
private data class PersistedRouteCards(
    val cards: List<PersistedRouteCard> = emptyList(),
)

@Serializable
private data class PersistedRouteCard(
    val key: String,
    val provider: String,
    val destinationName: String,
    val mode: String,
    val lastUsedAtMillis: Long,
) {
    fun toModel(): RouteCard? {
        val source = runCatching { NavigationEventSource.valueOf(provider) }.getOrNull()
            ?: return null
        val cardMode = runCatching { RouteCardMode.valueOf(mode) }.getOrNull()
            ?: return null
        val name = destinationName.trim()
        if (name.isEmpty()) return null
        return RouteCard(
            provider = source,
            destinationName = name,
            mode = cardMode,
            lastUsedAtMillis = lastUsedAtMillis,
        )
    }

    companion object {
        fun from(card: RouteCard) = PersistedRouteCard(
            key = card.key(),
            provider = card.provider.name,
            destinationName = card.destinationName,
            mode = card.mode.name,
            lastUsedAtMillis = card.lastUsedAtMillis,
        )
    }
}
