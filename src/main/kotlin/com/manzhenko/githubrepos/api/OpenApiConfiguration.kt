package com.manzhenko.githubrepos.api

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    @Bean
    fun githubRepositoriesApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("GitHub repositories API")
            .version("1.0.0")
            .description(
                "Reports the repositories a GitHub user owns, forks excluded, together with the " +
                    "branches of each repository and the last commit on every branch.",
            ),
    )
}
