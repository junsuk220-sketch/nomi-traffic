package nomi.android.traffic

/**
 * Reads a verified Naver HUD destination from already-collected screen blobs.
 * Uses `{place}` immediately before `까지 이동`. Does not use notification titles.
 */
object NaverTransitDestinationParser {

    private val formatChars = Regex("""[\u200b\u200c\u200d\ufeff]""")
    private val lineSuffix = Regex("""^(.+?)\s+\d+호선$""")
    private val untilMove = Regex("""^(.+?)\s*까지 이동$""")

    fun destination(root: NaverSubwayAccessibilityParser.Node): String? =
        destination(blobs(root))

    fun destination(blobs: List<String>): String? {
        val cleaned = blobs.map { clean(it) }.filter { it.isNotEmpty() }
        for (i in cleaned.indices) {
            if (cleaned[i] == "까지 이동" && i > 0) {
                displayName(cleaned[i - 1])?.let { return it }
            }
            untilMove.matchEntire(cleaned[i])?.let { match ->
                displayName(match.groupValues[1])?.let { return it }
            }
        }
        return null
    }

    fun isLiveGuidance(root: NaverSubwayAccessibilityParser.Node): Boolean =
        isLiveGuidance(blobs(root))

    fun isLiveGuidance(blobs: List<String>): Boolean =
        blobs.any {
            val text = clean(it)
            text == "안내 중" || text == "안내중"
        }

    internal fun clean(raw: String?): String =
        raw?.replace(formatChars, "")?.trim().orEmpty()

    internal fun displayName(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (!isPlausible(trimmed)) return null
        val withoutLine = lineSuffix.matchEntire(trimmed)?.groupValues?.get(1)?.trim().orEmpty()
        val name = withoutLine.ifEmpty { trimmed }
        if (!isPlausible(name)) return null
        return name
    }

    private fun isPlausible(name: String): Boolean {
        if (name.isEmpty()) return false
        if (name.contains("승차")) return false
        if (name.contains("방면")) return false
        if (name.contains("빠른")) return false
        if (name.contains("안내")) return false
        if (name == "미리보기" || name == "안내시작" || name == "까지 이동") return false
        return true
    }

    private fun blobs(node: NaverSubwayAccessibilityParser.Node): List<String> {
        val out = ArrayList<String>()
        collect(node, out)
        return out
    }

    private fun collect(node: NaverSubwayAccessibilityParser.Node, out: MutableList<String>) {
        clean(node.text).takeIf { it.isNotEmpty() }?.let(out::add)
        clean(node.contentDesc).takeIf { it.isNotEmpty() }?.let(out::add)
        node.children.forEach { collect(it, out) }
    }
}
