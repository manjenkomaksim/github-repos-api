package com.manzhenko.githubrepos.github

import org.springframework.http.HttpHeaders
import java.net.URI
import java.net.URISyntaxException

/**
 * Reads the `Link` header (RFC 8288) that GitHub uses to advertise the next page of a collection.
 * Following those links is what the API documentation recommends over building page URLs by hand
 */
internal object LinkHeader {

    private val ENTRY = Regex("""<([^>]*)>((?:\s*;\s*[^,;]+)*)""")
    private val RELATION = Regex("""rel\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)

    fun relation(headers: HttpHeaders, relation: String): URI? =
        headers.getOrEmpty(HttpHeaders.LINK)
            .asSequence()
            .flatMap { ENTRY.findAll(it) }
            .firstOrNull { it.declares(relation) }
            ?.groupValues
            ?.get(1)
            ?.let(::toUri)

    private fun MatchResult.declares(relation: String): Boolean =
        RELATION.find(groupValues[2])
            ?.groupValues
            ?.get(1)
            ?.split(' ')
            ?.any { it.equals(relation, ignoreCase = true) }
            ?: false

    private fun toUri(value: String): URI? =
        try {
            URI(value.trim())
        } catch (malformed: URISyntaxException) {
            null
        }
}
