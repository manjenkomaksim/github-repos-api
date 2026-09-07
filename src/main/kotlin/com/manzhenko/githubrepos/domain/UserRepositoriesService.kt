package com.manzhenko.githubrepos.domain

import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Collects the repositories a user owns together with the head of each of their branches.
 *
 * @param branchConcurrency how many branch listings may be in flight at once. Branches are read one
 * repository at a time, so the fan-out has to be bounded to stay friendly towards the GitHub API
 */
class UserRepositoriesService(
    private val github: GithubGateway,
    private val branchConcurrency: Int,
) {

    /**
     * Emits every repository owned by [username] that is not a fork, in the order GitHub lists them
     */
    fun listRepositories(username: String): Flux<Repository> =
        github.listRepositories(username)
            .filter { !it.isFork }
            .flatMapSequential(::withBranches, branchConcurrency)

    private fun withBranches(repository: RepositorySummary): Mono<Repository> =
        github.listBranches(repository.owner, repository.name)
            .collectList()
            .map { branches -> Repository(repository.name, repository.owner, branches) }
}
