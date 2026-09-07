package com.manzhenko.githubrepos.github

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.net.URI
import java.time.Duration

@ConfigurationProperties(prefix = "github")
data class GithubProperties(
    /** Root of the GitHub REST API */
    val baseUrl: URI = URI.create("https://api.github.com"),
    /** Personal access token. Optional, but anonymous clients get only 60 requests per hour */
    val token: String? = null,
    /** Page size asked from GitHub. 100 is the maximum the API accepts */
    val pageSize: Int = 100,
    /** How many branch listings may be in flight at the same time */
    val branchConcurrency: Int = 8,
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val responseTimeout: Duration = Duration.ofSeconds(10),
    /** A page of 100 repositories is far beyond the 256 KB the codecs buffer by default */
    val maxResponseSize: DataSize = DataSize.ofMegabytes(4),
    val retry: RetryProperties = RetryProperties(),
) {
    data class RetryProperties(
        /** Retries attempted after the initial request, for connection failures and 5xx only */
        val maxRetries: Long = 2,
        val minBackoff: Duration = Duration.ofMillis(200),
    )
}
