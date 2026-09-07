package com.manzhenko.githubrepos.api

import com.manzhenko.githubrepos.domain.Branch
import com.manzhenko.githubrepos.domain.GithubApiException
import com.manzhenko.githubrepos.domain.GithubRateLimitExceededException
import com.manzhenko.githubrepos.domain.GithubUnavailableException
import com.manzhenko.githubrepos.domain.GithubUserNotFoundException
import com.manzhenko.githubrepos.domain.Repository
import com.manzhenko.githubrepos.domain.UserRepositoriesService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Flux
import java.time.Duration

/**
 * Covers the HTTP contract: the shape of a successful answer, and the status, code and body of every
 * failure a client can run into
 */
@WebFluxTest(UserRepositoriesController::class)
class UserRepositoriesControllerTest {

    @Autowired
    private lateinit var client: WebTestClient

    @MockitoBean
    private lateinit var userRepositories: UserRepositoriesService

    @Test
    fun `returns the repositories of the user`() {
        whenever(userRepositories.listRepositories(OWNER)).thenReturn(
            Flux.just(
                Repository("hello-world", OWNER, listOf(Branch("main", "d0e1f2"), Branch("gh-pages", "a1b2c3"))),
                Repository("blank", OWNER, emptyList()),
            ),
        )

        client.get().uri(REPOSITORIES_OF_OWNER)
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
            .expectBody().json(
                """
                [
                  {
                    "name": "hello-world",
                    "owner": "octocat",
                    "branches": [
                      { "name": "main", "lastCommitSha": "d0e1f2" },
                      { "name": "gh-pages", "lastCommitSha": "a1b2c3" }
                    ]
                  },
                  { "name": "blank", "owner": "octocat", "branches": [] }
                ]
                """.trimIndent(),
                JsonCompareMode.STRICT,
            )

        verify(userRepositories).listRepositories(OWNER)
        verifyNoMoreInteractions(userRepositories)
    }

    @Test
    fun `returns an empty list for a user without repositories`() {
        whenever(userRepositories.listRepositories(OWNER)).thenReturn(Flux.empty())

        client.get().uri(REPOSITORIES_OF_OWNER)
            .exchange()
            .expectStatus().isOk
            .expectBody().json("[]", JsonCompareMode.STRICT)

        verify(userRepositories).listRepositories(OWNER)
    }

    @Test
    fun `answers with 404 when github does not know the user`() {
        whenever(userRepositories.listRepositories("ghost"))
            .thenReturn(Flux.error(GithubUserNotFoundException("ghost")))

        client.get().uri("/api/v1/users/ghost/repositories")
            .exchange()
            .expectStatus().isNotFound
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.code").isEqualTo("USER_NOT_FOUND")
            .jsonPath("$.message").isEqualTo("GitHub user 'ghost' was not found")
            .jsonPath("$.path").isEqualTo("/api/v1/users/ghost/repositories")
            .jsonPath("$.timestamp").exists()

        verify(userRepositories).listRepositories("ghost")
    }

    @Test
    fun `answers with 406 and a json body when the client asks for another representation`() {
        client.get().uri(REPOSITORIES_OF_OWNER)
            .accept(MediaType.APPLICATION_XML)
            .exchange()
            .expectStatus().isEqualTo(406)
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
            .expectBody()
            .jsonPath("$.code").isEqualTo("NOT_ACCEPTABLE")
            .jsonPath("$.message").isEqualTo("This API can only produce application/json")

        verifyNoInteractions(userRepositories)
    }

    @Test
    fun `serves json to a client that accepts anything`() {
        whenever(userRepositories.listRepositories(OWNER)).thenReturn(Flux.empty())

        client.get().uri(REPOSITORIES_OF_OWNER)
            .accept(MediaType.ALL)
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentType(MediaType.APPLICATION_JSON)

        verify(userRepositories).listRepositories(OWNER)
    }

    @Test
    fun `rejects a name that cannot be a github login`() {
        client.get().uri("/api/v1/users/not%20a%20login/repositories")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
            .jsonPath("$.message").isEqualTo("username must be a valid GitHub login")

        verifyNoInteractions(userRepositories)
    }

    @Test
    fun `answers with 429 and a retry hint when the github quota is exhausted`() {
        whenever(userRepositories.listRepositories(OWNER))
            .thenReturn(Flux.error(GithubRateLimitExceededException(Duration.ofSeconds(45))))

        client.get().uri(REPOSITORIES_OF_OWNER)
            .exchange()
            .expectStatus().isEqualTo(429)
            .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "45")
            .expectBody()
            .jsonPath("$.code").isEqualTo("UPSTREAM_RATE_LIMITED")

        verify(userRepositories).listRepositories(OWNER)
    }

    @Test
    fun `answers with 502 when github returns a failure`() {
        whenever(userRepositories.listRepositories(OWNER))
            .thenReturn(Flux.error(GithubApiException("GitHub API responded with 500", 500)))

        client.get().uri(REPOSITORIES_OF_OWNER)
            .exchange()
            .expectStatus().isEqualTo(502)
            .expectBody()
            .jsonPath("$.code").isEqualTo("UPSTREAM_ERROR")
            .jsonPath("$.message").isEqualTo("GitHub API responded with 500")

        verify(userRepositories).listRepositories(OWNER)
    }

    @Test
    fun `answers with 504 when github cannot be reached`() {
        whenever(userRepositories.listRepositories(OWNER))
            .thenReturn(Flux.error(GithubUnavailableException("GitHub API is unavailable")))

        client.get().uri(REPOSITORIES_OF_OWNER)
            .exchange()
            .expectStatus().isEqualTo(504)
            .expectBody()
            .jsonPath("$.code").isEqualTo("UPSTREAM_UNAVAILABLE")

        verify(userRepositories).listRepositories(OWNER)
    }

    @Test
    fun `answers with 500 without exposing the cause`() {
        whenever(userRepositories.listRepositories(OWNER))
            .thenReturn(Flux.error(IllegalStateException("a stack trace nobody outside should see")))

        client.get().uri(REPOSITORIES_OF_OWNER)
            .exchange()
            .expectStatus().is5xxServerError
            .expectBody()
            .jsonPath("$.status").isEqualTo(500)
            .jsonPath("$.code").isEqualTo("INTERNAL_ERROR")
            .jsonPath("$.message").isEqualTo("The request could not be processed")

        verify(userRepositories).listRepositories(OWNER)
    }

    @Test
    fun `answers with the same error document for an unknown path`() {
        client.get().uri("/api/v1/users/octocat")
            .exchange()
            .expectStatus().isNotFound
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
            .expectBody()
            .jsonPath("$.code").isEqualTo("RESOURCE_NOT_FOUND")
            .jsonPath("$.message").isEqualTo("No endpoint is mapped to GET /api/v1/users/octocat")
            .jsonPath("$.path").isEqualTo("/api/v1/users/octocat")

        verifyNoInteractions(userRepositories)
    }

    private companion object {
        const val OWNER = "octocat"
        const val REPOSITORIES_OF_OWNER = "/api/v1/users/$OWNER/repositories"
    }
}
