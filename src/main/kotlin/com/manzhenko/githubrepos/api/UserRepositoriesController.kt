package com.manzhenko.githubrepos.api

import com.manzhenko.githubrepos.domain.UserRepositoriesService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Pattern
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

/**
 * GitHub logins are alphanumeric with single inner hyphens and at most 39 characters. Rejecting
 * anything else here keeps malformed input from turning into a request to GitHub
 */
private const val GITHUB_USERNAME = "[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}"

@Tag(name = "Repositories", description = "Repositories owned by a GitHub user")
@RestController
@RequestMapping("/api/v1/users", produces = [MediaType.APPLICATION_JSON_VALUE])
class UserRepositoriesController(private val userRepositories: UserRepositoriesService) {

    @Operation(
        summary = "List the repositories of a user",
        description = "Returns the repositories the user owns, forks excluded, each with all of " +
            "its branches and the last commit on every branch.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Repositories of the user, empty if there are none"),
        ApiResponse(
            responseCode = "400",
            description = "The user name is not a valid GitHub login",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "No such GitHub user",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "406",
            description = "The client asked for a representation other than JSON",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "429",
            description = "The GitHub API rate limit is exhausted",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "502",
            description = "The GitHub API answered with an error",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "504",
            description = "The GitHub API could not be reached in time",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
    )
    @GetMapping("/{username}/repositories")
    fun listRepositories(
        @Parameter(description = "GitHub login of the account that owns the repositories", example = "octocat")
        @PathVariable
        @Pattern(regexp = GITHUB_USERNAME, message = "username must be a valid GitHub login")
        username: String,
    ): Mono<List<RepositoryResponse>> =
        userRepositories.listRepositories(username)
            .map(RepositoryResponse::from)
            .collectList()
}
