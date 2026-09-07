package com.manzhenko.githubrepos.api

import com.manzhenko.githubrepos.domain.GithubApiException
import com.manzhenko.githubrepos.domain.GithubRateLimitExceededException
import com.manzhenko.githubrepos.domain.GithubUnavailableException
import com.manzhenko.githubrepos.domain.GithubUserNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.server.NotAcceptableStatusException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import java.time.Duration
import java.time.Instant

/**
 * Renders every failure as the same [ApiError] document. The content type is set explicitly so that
 * a client asking for a representation this service cannot produce still receives a readable body
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(GithubUserNotFoundException::class)
    fun handleUserNotFound(error: GithubUserNotFoundException, exchange: ServerWebExchange) =
        respond(HttpStatus.NOT_FOUND, ErrorCode.USER_NOT_FOUND, error.message, exchange)

    @ExceptionHandler(GithubRateLimitExceededException::class)
    fun handleRateLimited(error: GithubRateLimitExceededException, exchange: ServerWebExchange) =
        respond(
            status = HttpStatus.TOO_MANY_REQUESTS,
            code = ErrorCode.UPSTREAM_RATE_LIMITED,
            message = error.message,
            exchange = exchange,
            retryAfter = error.retryAfter,
        )

    @ExceptionHandler(GithubUnavailableException::class)
    fun handleUpstreamUnavailable(error: GithubUnavailableException, exchange: ServerWebExchange) =
        respond(HttpStatus.GATEWAY_TIMEOUT, ErrorCode.UPSTREAM_UNAVAILABLE, error.message, exchange)

    @ExceptionHandler(GithubApiException::class)
    fun handleUpstreamFailure(error: GithubApiException, exchange: ServerWebExchange) =
        respond(HttpStatus.BAD_GATEWAY, ErrorCode.UPSTREAM_ERROR, error.message, exchange)

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun handleInvalidRequest(error: HandlerMethodValidationException, exchange: ServerWebExchange) =
        respond(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, error.describe(), exchange)

    @ExceptionHandler(NotAcceptableStatusException::class)
    fun handleNotAcceptable(error: NotAcceptableStatusException, exchange: ServerWebExchange) =
        respond(
            status = HttpStatus.NOT_ACCEPTABLE,
            code = ErrorCode.NOT_ACCEPTABLE,
            message = "This API can only produce ${MediaType.APPLICATION_JSON_VALUE}",
            exchange = exchange,
        )

    /** Covers the failures Spring raises before a controller is reached, such as an unknown path */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(error: ResponseStatusException, exchange: ServerWebExchange): ResponseEntity<ApiError> {
        val message = if (HttpStatus.NOT_FOUND.isSameCodeAs(error.statusCode)) {
            "No endpoint is mapped to ${exchange.request.method} ${exchange.request.path.value()}"
        } else {
            error.reason ?: HttpStatus.resolve(error.statusCode.value())?.reasonPhrase ?: "The request was rejected"
        }
        return respond(error.statusCode, codeOf(error.statusCode), message, exchange)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(error: Exception, exchange: ServerWebExchange): ResponseEntity<ApiError> {
        log.error("Unhandled failure while serving {}", exchange.request.path.value(), error)
        return respond(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            code = ErrorCode.INTERNAL_ERROR,
            message = "The request could not be processed",
            exchange = exchange,
        )
    }

    private fun respond(
        status: HttpStatusCode,
        code: ErrorCode,
        message: String,
        exchange: ServerWebExchange,
        retryAfter: Duration? = null,
    ): ResponseEntity<ApiError> {
        val response = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
        retryAfter?.let { response.header(HttpHeaders.RETRY_AFTER, it.toSeconds().coerceAtLeast(1).toString()) }
        return response.body(
            ApiError(
                timestamp = Instant.now(),
                status = status.value(),
                code = code,
                message = message,
                path = exchange.request.path.value(),
            ),
        )
    }

    private fun codeOf(status: HttpStatusCode): ErrorCode = when {
        HttpStatus.NOT_FOUND.isSameCodeAs(status) -> ErrorCode.RESOURCE_NOT_FOUND
        HttpStatus.METHOD_NOT_ALLOWED.isSameCodeAs(status) -> ErrorCode.METHOD_NOT_ALLOWED
        HttpStatus.NOT_ACCEPTABLE.isSameCodeAs(status) -> ErrorCode.NOT_ACCEPTABLE
        status.is4xxClientError -> ErrorCode.INVALID_REQUEST
        else -> ErrorCode.INTERNAL_ERROR
    }

    private fun HandlerMethodValidationException.describe(): String =
        parameterValidationResults
            .flatMap { it.resolvableErrors }
            .mapNotNull { it.defaultMessage }
            .joinToString(", ")
            .ifBlank { "Request parameters are invalid" }
}
