package com.weatherwidget.data.repository

/**
 * Cleans pasted/typed address strings for Nominatim forward geocoding.
 *
 * Nominatim returns empty results for queries containing secondary unit designators (`Unit 1182.1`,
 * `Apt 4B`, `Suite 200`, `#12`) and for unmapped house numbers. This helper keeps single-line typing
 * untouched, flattens pasted multi-line addresses into comma-separated segments, and builds a small
 * ordered fallback list so [SharedLocationResolver.searchText] can recover automatically.
 */
object AddressQueryCleaner {
    private val LINE_BREAK_REGEX = Regex("\\r\\n|[\\r\\n]")
    private val MULTI_SPACE_REGEX = Regex("[ \\t]+")
    private val COMMA_SPACING_REGEX = Regex("\\s*,\\s*")
    private val MULTI_COMMA_REGEX = Regex("(?:,\\s*){2,}")

    private val UNIT_KEYWORD_REGEX = Regex(
        pattern = "(?:^|[\\s,]+)(?:unit|apt\\.?|apartment|suite|ste\\.?|room|rm\\.?|floor|fl\\.?|bldg\\.?|building|lot|space|spc\\.?|trlr\\.?|trailer)\\s*#?\\s*[A-Za-z0-9][A-Za-z0-9.\\-/]*",
        option = RegexOption.IGNORE_CASE,
    )
    private val HASH_UNIT_REGEX = Regex("(?:^|[\\s,]+)#\\s*[A-Za-z0-9][A-Za-z0-9.\\-/]*")

    /**
     * Flattens multi-line pastes into a single comma-separated line while leaving single-line typing
     * (including trailing spaces) untouched.
     */
    fun flattenMultilineInput(input: String): String {
        if (!input.contains('\n') && !input.contains('\r')) return input
        return input
            .split(LINE_BREAK_REGEX)
            .map { it.trim().trimEnd(',').trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")
    }

    /**
     * Builds an ordered, deduplicated list of queries to try against Nominatim:
     * 1. Normalized query (newlines flattened, spaces and commas tidied).
     * 2. Secondary unit designators stripped (`Unit 1182.1`, `Apt 4B`, `#12`, etc.).
     * 3. Leading comma-separated segment dropped (city/state/ZIP fallback when a street isn't in OSM).
     */
    fun candidateQueries(rawQuery: String): List<String> {
        val normalized = normalizeWhitespace(rawQuery)
        if (normalized.isEmpty()) return emptyList()

        val candidates = LinkedHashSet<String>()
        candidates.add(normalized)

        val withoutUnit = stripUnitDesignators(normalized)
        if (withoutUnit.isNotEmpty()) {
            candidates.add(withoutUnit)
        }

        val baseForFallback = withoutUnit.ifEmpty { normalized }
        dropLeadingSegment(baseForFallback)?.let { candidates.add(it) }

        return candidates.toList()
    }

    internal fun normalizeWhitespace(input: String): String {
        val flattened = flattenMultilineInput(input)
        return flattened
            .replace(MULTI_SPACE_REGEX, " ")
            .replace(COMMA_SPACING_REGEX, ", ")
            .replace(MULTI_COMMA_REGEX, ", ")
            .trim()
            .trim(',')
            .trim()
    }

    internal fun stripUnitDesignators(input: String): String {
        val stripped = input
            .replace(UNIT_KEYWORD_REGEX, " ")
            .replace(HASH_UNIT_REGEX, " ")
        return normalizeWhitespace(stripped)
    }

    private fun dropLeadingSegment(input: String): String? {
        val parts = input.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val rest = parts.drop(1).joinToString(", ")
        return rest.takeIf { it.length >= 2 }
    }
}
