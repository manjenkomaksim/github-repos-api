package com.manzhenko.githubrepos.domain

import reactor.core.publisher.Flux

/**
 * Outbound port for reading repository data from GitHub.
 *
 * Repositories and branches are exposed as two separate operations on purpose: it lets the
 * application layer discard forks before paying for their branch lookups
 */
interface GithubGateway {

    /**
     * Emits every repository owned by [username], forks included. The stream fails with
     * [GithubUserNotFoundException] when GitHub does not know the user
     */
    fun listRepositories(username: String): Flux<RepositorySummary>

    /**
     * Emits every branch of [owner]/[repository], or nothing if the repository is gone
     */
    fun listBranches(owner: String, repository: String): Flux<Branch>
}

/**
 * A repository as it appears in a listing, before its branches are known
 */
data class RepositorySummary(
    val name: String,
    val owner: String,
    val isFork: Boolean,
)
