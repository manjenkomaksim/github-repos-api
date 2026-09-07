package com.manzhenko.githubrepos

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * Drives the running application over HTTP against a stubbed GitHub, so the wiring, the paging and
 * the response contract are checked together rather than one layer at a time
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserRepositoriesIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private lateinit var client: WebTestClient

    @BeforeEach
    fun resetStubs() {
        github.resetAll()
        client = WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()
    }

    @Test
    fun `serves the repositories a user owns, forks left out`() {
        githubReturnsTwoPagesOfRepositories()
        githubReturnsBranchesOf("hello-world")
        githubReturnsBranchesOf("spring-boot")

        client.get().uri("/api/v1/users/octocat/repositories")
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
                      { "name": "main", "lastCommitSha": "7fd1a60b01f91b314f59955a4e4d4e80d8edf11d" },
                      { "name": "gh-pages", "lastCommitSha": "1e2d3c4b5a69788796a5b4c3d2e1f0a9b8c7d6e5" }
                    ]
                  },
                  {
                    "name": "spring-boot",
                    "owner": "octocat",
                    "branches": [
                      { "name": "main", "lastCommitSha": "3f1a2b9c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f3a" }
                    ]
                  }
                ]
                """.trimIndent(),
                JsonCompareMode.STRICT,
            )

        github.verify(2, getRequestedFor(urlPathEqualTo(REPOSITORIES)))
        github.verify(0, getRequestedFor(urlPathEqualTo("/repos/octocat/linux/branches")))
    }

    @Test
    fun `answers with 404 when github does not know the user`() {
        github.stubFor(get(urlPathEqualTo("/users/ghost/repos")).willReturn(aResponse().withStatus(404)))

        client.get().uri("/api/v1/users/ghost/repositories")
            .exchange()
            .expectStatus().isNotFound
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
            .expectBody()
            .jsonPath("$.code").isEqualTo("USER_NOT_FOUND")
            .jsonPath("$.message").isEqualTo("GitHub user 'ghost' was not found")
    }

    @Test
    fun `answers with 406 when the client asks for a representation the api does not produce`() {
        client.get().uri("/api/v1/users/octocat/repositories")
            .accept(MediaType.APPLICATION_XML)
            .exchange()
            .expectStatus().isEqualTo(406)
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
            .expectBody()
            .jsonPath("$.code").isEqualTo("NOT_ACCEPTABLE")
    }

    @Test
    fun `describes itself with an openapi document`() {
        client.get().uri("/v3/api-docs")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.paths['/api/v1/users/{username}/repositories'].get").exists()
            .jsonPath("$.components.schemas.Repository").exists()
            .jsonPath("$.components.schemas.ApiError").exists()
    }

    private fun githubReturnsTwoPagesOfRepositories() {
        github.stubFor(
            get(urlPathEqualTo(REPOSITORIES))
                .withQueryParam("per_page", equalTo("100"))
                .withQueryParam("page", absent())
                .willReturn(
                    aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("repositories-page-1.json")
                        .withHeader("Link", """<${github.baseUrl()}$REPOSITORIES?per_page=100&page=2>; rel="next""""),
                ),
        )
        github.stubFor(
            get(urlPathEqualTo(REPOSITORIES))
                .withQueryParam("page", equalTo("2"))
                .willReturn(
                    aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("repositories-page-2.json"),
                ),
        )
    }

    private fun githubReturnsBranchesOf(repository: String) {
        github.stubFor(
            get(urlPathEqualTo("/repos/octocat/$repository/branches")).willReturn(
                aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBodyFile("branches-$repository.json"),
            ),
        )
    }

    private companion object {
        const val REPOSITORIES = "/users/octocat/repos"

        val github = WireMockServer(options().dynamicPort().usingFilesUnderClasspath("wiremock"))

        @BeforeAll
        @JvmStatic
        fun startGithub() = github.start()

        @AfterAll
        @JvmStatic
        fun stopGithub() = github.stop()

        @DynamicPropertySource
        @JvmStatic
        fun githubLocation(registry: DynamicPropertyRegistry) {
            registry.add("github.base-url") { github.baseUrl() }
        }
    }
}
