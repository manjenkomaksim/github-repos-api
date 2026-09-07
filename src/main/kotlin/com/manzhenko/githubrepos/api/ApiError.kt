package com.manzhenko.githubrepos.api

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@Schema(name = "ApiError", description = "Representation returned by every request that fails")
data class ApiError(
    @field:Schema(description = "Moment the failure was rendered", example = "2026-01-31T10:15:30.123Z")
    val timestamp: Instant,
    @field:Schema(description = "HTTP status code, repeated for clients that only record the body", example = "404")
    val status: Int,
    @field:Schema(description = "Stable identifier of the failure, safe to branch on")
    val code: ErrorCode,
    @field:Schema(description = "Explanation meant for humans", example = "GitHub user 'octocat' was not found")
    val message: String,
    @field:Schema(description = "Path of the request that failed", example = "/api/v1/users/octocat/repositories")
    val path: String,
)

/**
 * Closed set of failure reasons. Codes are part of the published contract, HTTP statuses alone are
 * too coarse to tell, for example, a missing user from a missing endpoint
 */
enum class ErrorCode {
    INVALID_REQUEST,
    RESOURCE_NOT_FOUND,
    USER_NOT_FOUND,
    METHOD_NOT_ALLOWED,
    NOT_ACCEPTABLE,
    UPSTREAM_RATE_LIMITED,
    UPSTREAM_ERROR,
    UPSTREAM_UNAVAILABLE,
    INTERNAL_ERROR,
}
