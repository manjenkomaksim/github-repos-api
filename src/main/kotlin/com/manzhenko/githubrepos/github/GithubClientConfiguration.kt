package com.manzhenko.githubrepos.github

import io.netty.channel.ChannelOption
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient

/** Media type that pins the GitHub response format, as recommended by the API documentation */
private const val GITHUB_JSON = "application/vnd.github+json"
private const val GITHUB_API_VERSION_HEADER = "X-GitHub-Api-Version"

/** Current version of the REST API, as listed by `GET https://api.github.com/versions` */
private const val GITHUB_API_VERSION = "2026-03-10"
private const val USER_AGENT = "github-repos-api"

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GithubProperties::class)
class GithubClientConfiguration {

    @Bean
    fun githubWebClient(builder: WebClient.Builder, properties: GithubProperties): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.connectTimeout.toMillis().toInt())
            .responseTimeout(properties.responseTimeout)

        val githubClient = builder
            .baseUrl(properties.baseUrl.toString().trimEnd('/'))
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .codecs { it.defaultCodecs().maxInMemorySize(properties.maxResponseSize.toBytes().toInt()) }
            .defaultHeader(HttpHeaders.ACCEPT, GITHUB_JSON)
            .defaultHeader(GITHUB_API_VERSION_HEADER, GITHUB_API_VERSION)
            .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)

        properties.token
            ?.takeIf(String::isNotBlank)
            ?.let { githubClient.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $it") }

        return githubClient.build()
    }
}
