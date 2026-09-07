package com.manzhenko.githubrepos.github

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import java.net.URI

class LinkHeaderTest {

    @Test
    fun `reads the location of the next page`() {
        val headers = linkHeader(
            """<https://api.github.com/user/repos?page=2>; rel="next", """ +
                """<https://api.github.com/user/repos?page=9>; rel="last"""",
        )

        assertThat(LinkHeader.relation(headers, "next"))
            .isEqualTo(URI("https://api.github.com/user/repos?page=2"))
    }

    @Test
    fun `ignores relations that were not asked for`() {
        val headers = linkHeader("""<https://api.github.com/user/repos?page=1>; rel="prev"""")

        assertThat(LinkHeader.relation(headers, "next")).isNull()
    }

    @Test
    fun `returns nothing when the response carries no links`() {
        assertThat(LinkHeader.relation(HttpHeaders.EMPTY, "next")).isNull()
    }

    @Test
    fun `accepts unquoted relations and additional parameters`() {
        val headers = linkHeader("""<https://api.github.com/user/repos?page=4>; type=application/json; rel=next""")

        assertThat(LinkHeader.relation(headers, "next"))
            .isEqualTo(URI("https://api.github.com/user/repos?page=4"))
    }

    private fun linkHeader(value: String) = HttpHeaders().apply { add(HttpHeaders.LINK, value) }
}
