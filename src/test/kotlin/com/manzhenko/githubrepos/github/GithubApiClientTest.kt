package com.manzhenko.githubrepos.github

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED
import com.manzhenko.githubrepos.domain.Branch
import com.manzhenko.githubrepos.domain.GithubApiException
import com.manzhenko.githubrepos.domain.GithubRateLimitExceededException
import com.manzhenko.githubrepos.domain.GithubUnavailableException
import com.manzhenko.githubrepos.domain.GithubUserNotFoundException
import com.manzhenko.githubrepos.domain.RepositorySummary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import reactor.test.StepVerifier
import java.net.URI
import java.time.Duration
import java.time.Instant

/**
 * Runs the adapter against a stubbed GitHub. Paging, the request contract and the translation of
 * upstream failures can only be shown over a real HTTP round trip
 */
class GithubApiClientTest {

    @BeforeEach
    fun resetStubs() {
        github.resetAll()
    }

    @Test
    fun `follows the link header until the last page of repositories`() {
        github.stubFor(
            get(urlPathEqualTo(REPOS_PATH))
                .withQueryParam("per_page", equalTo("2"))
                .withQueryParam("page", absent())
                .willReturn(
                    okJson(repositories("hello-world" to false, "linux" to true))
                        .withHeader("Link", nextPageLink("$REPOS_PATH?per_page=2&page=2")),
                ),
        )
        github.stubFor(
            get(urlPathEqualTo(REPOS_PATH))
                .withQueryParam("page", equalTo("2"))
                .willReturn(okJson(repositories("spring-boot" to false))),
        )

        StepVerifier.create(client().listRepositories(OWNER))
            .expectNext(RepositorySummary("hello-world", OWNER, isFork = false))
            .expectNext(RepositorySummary("linux", OWNER, isFork = true))
            .expectNext(RepositorySummary("spring-boot", OWNER, isFork = false))
            .verifyComplete()

        github.verify(2, getRequestedFor(urlPathEqualTo(REPOS_PATH)))
    }

    @Test
    fun `sends the headers the github api expects`() {
        val properties = properties(token = "s3cret")
        github.stubFor(get(urlPathEqualTo(REPOS_PATH)).willReturn(okJson(repositories())))

        client(properties).listRepositories(OWNER).blockLast()

        github.verify(
            getRequestedFor(urlPathEqualTo(REPOS_PATH))
                .withHeader("Accept", equalTo("application/vnd.github+json"))
                .withHeader("X-GitHub-Api-Version", equalTo("2026-03-10"))
                .withHeader("User-Agent", equalTo("github-repos-api"))
                .withHeader("Authorization", equalTo("Bearer s3cret")),
        )
    }

    @Test
    fun `ignores a pagination link that leaves the configured host`() {
        github.stubFor(
            get(urlPathEqualTo(REPOS_PATH)).willReturn(
                okJson(repositories("hello-world" to false))
                    .withHeader("Link", """<https://elsewhere.example.com/users/$OWNER/repos?page=2>; rel="next""""),
            ),
        )

        StepVerifier.create(client().listRepositories(OWNER))
            .expectNext(RepositorySummary("hello-world", OWNER, isFork = false))
            .verifyComplete()

        github.verify(1, getRequestedFor(urlPathEqualTo(REPOS_PATH)))
    }

    @Test
    fun `reports a user github does not know`() {
        github.stubFor(get(urlPathEqualTo(REPOS_PATH)).willReturn(aResponse().withStatus(404)))

        StepVerifier.create(client().listRepositories(OWNER))
            .consumeErrorWith { error ->
                assertThat(error).isInstanceOf(GithubUserNotFoundException::class.java)
                assertThat((error as GithubUserNotFoundException).username).isEqualTo(OWNER)
            }
            .verify()
    }

    @Test
    fun `gives up on a failing github after the configured retries`() {
        github.stubFor(get(urlPathEqualTo(REPOS_PATH)).willReturn(aResponse().withStatus(503)))

        StepVerifier.create(client().listRepositories(OWNER))
            .consumeErrorWith { error ->
                assertThat(error).isInstanceOf(GithubApiException::class.java)
                assertThat((error as GithubApiException).statusCode).isEqualTo(503)
            }
            .verify()

        github.verify(2, getRequestedFor(urlPathEqualTo(REPOS_PATH)))
    }

    @Test
    fun `retries a failure that looks temporary`() {
        github.stubFor(
            get(urlPathEqualTo(REPOS_PATH)).inScenario("flaky")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("recovered"),
        )
        github.stubFor(
            get(urlPathEqualTo(REPOS_PATH)).inScenario("flaky")
                .whenScenarioStateIs("recovered")
                .willReturn(okJson(repositories("hello-world" to false))),
        )

        StepVerifier.create(client().listRepositories(OWNER))
            .expectNext(RepositorySummary("hello-world", OWNER, isFork = false))
            .verifyComplete()
    }

    @Test
    fun `reports an exhausted rate limit with the moment the quota returns`() {
        github.stubFor(
            get(urlPathEqualTo(REPOS_PATH)).willReturn(
                aResponse().withStatus(403)
                    .withHeader("x-ratelimit-remaining", "0")
                    .withHeader("x-ratelimit-reset", (Instant.now().epochSecond + 60).toString()),
            ),
        )

        StepVerifier.create(client().listRepositories(OWNER))
            .consumeErrorWith { error ->
                assertThat(error).isInstanceOf(GithubRateLimitExceededException::class.java)
                assertThat((error as GithubRateLimitExceededException).retryAfter)
                    .isBetween(Duration.ofSeconds(1), Duration.ofSeconds(60))
            }
            .verify()

        github.verify(1, getRequestedFor(urlPathEqualTo(REPOS_PATH)))
    }

    @Test
    fun `reports a throttled burst as a rate limit as well`() {
        github.stubFor(
            get(urlPathEqualTo(REPOS_PATH))
                .willReturn(aResponse().withStatus(429).withHeader("retry-after", "12")),
        )

        StepVerifier.create(client().listRepositories(OWNER))
            .consumeErrorWith { error ->
                assertThat((error as GithubRateLimitExceededException).retryAfter).isEqualTo(Duration.ofSeconds(12))
            }
            .verify()
    }

    @Test
    fun `reports a payload that no longer matches as an upstream failure`() {
        github.stubFor(
            get(urlPathEqualTo(REPOS_PATH)).willReturn(okJson("""[{"renamed_field": "hello-world"}]""")),
        )

        StepVerifier.create(client().listRepositories(OWNER))
            .consumeErrorWith { error ->
                assertThat(error).isInstanceOf(GithubApiException::class.java)
                assertThat(error).hasMessage("GitHub API returned a response that could not be read")
            }
            .verify()
    }

    @Test
    fun `reports a response that is not json as an upstream failure`() {
        github.stubFor(
            get(urlPathEqualTo(REPOS_PATH)).willReturn(
                aResponse().withStatus(200)
                    .withHeader("Content-Type", "text/html")
                    .withBody("<html><body>Gateway timeout</body></html>"),
            ),
        )

        StepVerifier.create(client().listRepositories(OWNER))
            .consumeErrorWith { error ->
                assertThat(error).isInstanceOf(GithubApiException::class.java)
                assertThat(error).hasMessage("GitHub API returned a response that could not be read")
            }
            .verify()
    }

    @Test
    fun `reports a github that does not answer in time as unavailable`() {
        github.stubFor(
            get(urlPathEqualTo(REPOS_PATH))
                .willReturn(okJson(repositories()).withFixedDelay(500)),
        )

        val impatient = client(properties(responseTimeout = Duration.ofMillis(100), maxRetries = 0))

        StepVerifier.create(impatient.listRepositories(OWNER))
            .verifyError(GithubUnavailableException::class.java)
    }

    @Test
    fun `reads the last commit of every branch`() {
        github.stubFor(
            get(urlPathEqualTo("/repos/$OWNER/hello-world/branches"))
                .withQueryParam("per_page", equalTo("2"))
                .willReturn(okJson(branches("main" to "d0e1f2", "gh-pages" to "a1b2c3"))),
        )

        StepVerifier.create(client().listBranches(OWNER, "hello-world"))
            .expectNext(Branch("main", "d0e1f2"))
            .expectNext(Branch("gh-pages", "a1b2c3"))
            .verifyComplete()
    }

    @Test
    fun `treats a repository that disappeared as one without branches`() {
        github.stubFor(
            get(urlPathEqualTo("/repos/$OWNER/hello-world/branches")).willReturn(aResponse().withStatus(404)),
        )

        StepVerifier.create(client().listBranches(OWNER, "hello-world"))
            .verifyComplete()
    }

    private fun client(properties: GithubProperties = properties()) =
        GithubApiClient(GithubClientConfiguration().githubWebClient(WebClient.builder(), properties), properties)

    private fun properties(
        token: String? = null,
        responseTimeout: Duration = Duration.ofSeconds(5),
        maxRetries: Long = 1,
    ) = GithubProperties(
        baseUrl = URI(github.baseUrl()),
        token = token,
        pageSize = 2,
        responseTimeout = responseTimeout,
        retry = GithubProperties.RetryProperties(maxRetries = maxRetries, minBackoff = Duration.ofMillis(10)),
    )

    private fun nextPageLink(path: String) = """<${github.baseUrl()}$path>; rel="next""""

    /** Trimmed GitHub payloads, including a few fields the client ignores */
    private fun repositories(vararg repositories: Pair<String, Boolean>) =
        repositories.joinToString(prefix = "[", postfix = "]") { (name, fork) ->
            """{"id": 1296269, "name": "$name", "full_name": "$OWNER/$name", "private": false,
               |"fork": $fork, "owner": {"login": "$OWNER", "id": 583231}}
            """.trimMargin()
        }

    private fun branches(vararg branches: Pair<String, String>) =
        branches.joinToString(prefix = "[", postfix = "]") { (name, sha) ->
            """{"name": "$name", "protected": false,
               |"commit": {"sha": "$sha", "url": "https://api.github.com/commits/$sha"}}
            """.trimMargin()
        }

    private companion object {
        const val OWNER = "octocat"
        const val REPOS_PATH = "/users/$OWNER/repos"

        val github = WireMockServer(options().dynamicPort())

        @BeforeAll
        @JvmStatic
        fun startGithub() = github.start()

        @AfterAll
        @JvmStatic
        fun stopGithub() = github.stop()
    }
}
