package com.manzhenko.githubrepos

import com.manzhenko.githubrepos.domain.GithubGateway
import com.manzhenko.githubrepos.domain.UserRepositoriesService
import com.manzhenko.githubrepos.github.GithubProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Composition root. The domain package is deliberately free of Spring annotations, so its beans are
 * assembled here instead of being discovered by a component scan
 */
@Configuration(proxyBeanMethods = false)
class ApplicationConfiguration {

    @Bean
    fun userRepositoriesService(github: GithubGateway, properties: GithubProperties) =
        UserRepositoriesService(github, properties.branchConcurrency)
}
