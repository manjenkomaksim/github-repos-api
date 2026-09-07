package com.manzhenko.githubrepos.github

import com.manzhenko.githubrepos.domain.Branch
import com.manzhenko.githubrepos.domain.GithubApiException
import com.manzhenko.githubrepos.domain.GithubException
import com.manzhenko.githubrepos.domain.GithubGateway
import com.manzhenko.githubrepos.domain.GithubRateLimitExceededException
import com.manzhenko.githubrepos.domain.GithubUnavailableException
import com.manzhenko.githubrepos.domain.GithubUserNotFoundException
import com.manzhenko.githubrepos.domain.RepositorySummary
import org.slf4j.LoggerFactory
import org.springframework.core.ParameterizedTypeReference
import org.springframework.core.codec.CodecException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import java.net.URI
import java.time.Duration
import java.time.Instant

private const val PER_PAGE = "per_page"
private const val NEXT_RELATION = "next"
private const val RATE_LIMIT_REMAINING = "x-ratelimit-remaining"
private const val RATE_LIMIT_RESET = "x-ratelimit-reset"

private val REPOSITORY_PAGE = object : ParameterizedTypeReference<List<GithubRepositoryPayload>>() {}
private val BRANCH_PAGE = object : ParameterizedTypeReference<List<GithubBranchPayload>>() {}

/** A page of results together with the location of the page that follows it, if any */
private data class Page<T>(val items: List<T>, val next: URI?)

/**
 * Reads repositories and branches from the GitHub REST API and translates its failures into domain
 * terms, so that no `WebClient` type leaves this class
 */
@Component
class GithubApiClient(
    private val githubWebClient: WebClient,
    private val properties: GithubProperties,
) : GithubGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    private val retry: Retry = Retry
        .backoff(properties.retry.maxRetries, properties.retry.minBackoff)
        .filter(::isTransient)
        .onRetryExhaustedThrow { _, signal -> signal.failure() }

    override fun listRepositories(username: String): Flux<RepositorySummary> =
        paginate(REPOSITORY_PAGE, "/users/{username}/repos", mapOf("username" to username))
            .onErrorMap(WebClientResponseException.NotFound::class.java) { GithubUserNotFoundException(username) }
            .map { RepositorySummary(name = it.name, owner = it.owner.login, isFork = it.fork) }
            .onErrorMap(::asDomainFailure)

    override fun listBranches(owner: String, repository: String): Flux<Branch> =
        paginate(
            BRANCH_PAGE,
            "/repos/{owner}/{repository}/branches",
            mapOf("owner" to owner, "repository" to repository),
        )
            .onErrorResume(WebClientResponseException.NotFound::class.java) {
                log.warn("Repository {}/{} is no longer available, reporting it without branches", owner, repository)
                Flux.empty()
            }
            .map { Branch(name = it.name, lastCommitSha = it.commit.sha) }
            .onErrorMap(::asDomainFailure)

    /**
     * Walks a GitHub collection page by page. Pages have to be requested one after another because
     * only the response reveals whether another page exists
     */
    private fun <T : Any> paginate(
        pageType: ParameterizedTypeReference<List<T>>,
        path: String,
        uriVariables: Map<String, String>,
    ): Flux<T> {
        val firstPage = fetchPage(pageType) { spec ->
            spec.uri { uri -> uri.path(path).queryParam(PER_PAGE, properties.pageSize).build(uriVariables) }
        }
        return firstPage
            .expand { page ->
                page.next?.let { next -> fetchPage(pageType) { spec -> spec.uri(next) } } ?: Mono.empty()
            }
            .concatMapIterable(Page<T>::items)
    }

    private fun <T : Any> fetchPage(
        pageType: ParameterizedTypeReference<List<T>>,
        target: (WebClient.RequestHeadersUriSpec<*>) -> WebClient.RequestHeadersSpec<*>,
    ): Mono<Page<T>> =
        target(githubWebClient.get())
            .retrieve()
            .toEntity(pageType)
            .map { response -> Page(response.body.orEmpty(), nextPage(response.headers)) }
            .retryWhen(retry)

    private fun nextPage(headers: HttpHeaders): URI? {
        val next = LinkHeader.relation(headers, NEXT_RELATION) ?: return null
        if (!isSameOrigin(next)) {
            log.warn("Ignoring pagination link pointing outside of the configured GitHub host: {}", next)
            return null
        }
        return next
    }

    private fun isSameOrigin(uri: URI): Boolean =
        uri.scheme.equals(properties.baseUrl.scheme, ignoreCase = true) &&
            uri.authority.equals(properties.baseUrl.authority, ignoreCase = true)

    private fun isTransient(error: Throwable): Boolean = when (error) {
        is WebClientRequestException -> true
        is WebClientResponseException -> error.statusCode.is5xxServerError
        else -> false
    }

    private fun asDomainFailure(error: Throwable): Throwable = when (error) {
        is GithubException -> error
        is WebClientResponseException -> translate(error)
        is WebClientRequestException -> {
            log.warn("GitHub API could not be reached at {}", error.uri, error)
            GithubUnavailableException("GitHub API is unavailable", error)
        }
        // A payload that no longer fits the model the client was written against
        is CodecException -> unreadable(error)
        else -> error
    }

    private fun translate(error: WebClientResponseException): GithubException = when {
        isRateLimited(error) -> GithubRateLimitExceededException(retryAfter(error.headers))
        // A body the client cannot decode surfaces as a failure that still carries the original,
        // successful status, so the status alone does not say what went wrong
        !error.statusCode.isError -> unreadable(error)
        else -> {
            log.warn("GitHub API responded with {} for {}", error.statusCode, error.request?.uri)
            GithubApiException("GitHub API responded with ${error.statusCode.value()}", error.statusCode.value())
        }
    }

    private fun unreadable(error: Throwable): GithubApiException {
        log.warn("GitHub API returned a response that could not be read", error)
        return GithubApiException("GitHub API returned a response that could not be read")
    }

    /**
     * GitHub reports an exhausted quota with 403 and a depleted rate limit counter, and throttles
     * bursts with 429
     */
    private fun isRateLimited(error: WebClientResponseException): Boolean =
        HttpStatus.TOO_MANY_REQUESTS.isSameCodeAs(error.statusCode) ||
            (HttpStatus.FORBIDDEN.isSameCodeAs(error.statusCode) &&
                error.headers.getFirst(RATE_LIMIT_REMAINING) == "0")

    private fun retryAfter(headers: HttpHeaders): Duration? =
        headers.getFirst(HttpHeaders.RETRY_AFTER)?.toLongOrNull()?.let(Duration::ofSeconds)
            ?: headers.getFirst(RATE_LIMIT_RESET)?.toLongOrNull()
                ?.let { Duration.between(Instant.now(), Instant.ofEpochSecond(it)) }
                ?.takeIf { !it.isNegative }
}
