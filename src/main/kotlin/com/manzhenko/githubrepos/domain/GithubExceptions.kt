package com.manzhenko.githubrepos.domain

import java.time.Duration

/**
 * Failures the application can attribute to GitHub. Anything else that escapes the domain is a bug
 * and is reported as an internal error
 */
sealed class GithubException(override val message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class GithubUserNotFoundException(val username: String) :
    GithubException("GitHub user '$username' was not found")

/** The configured credentials ran out of GitHub API quota */
class GithubRateLimitExceededException(val retryAfter: Duration?) :
    GithubException("GitHub API rate limit exceeded")

/** GitHub answered, but not with something we can use */
class GithubApiException(message: String, val statusCode: Int? = null) : GithubException(message)

/** GitHub could not be reached at all, or did not answer in time */
class GithubUnavailableException(message: String, cause: Throwable? = null) : GithubException(message, cause)
