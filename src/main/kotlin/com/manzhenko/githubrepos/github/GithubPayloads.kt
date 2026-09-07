package com.manzhenko.githubrepos.github

/**
 * The part of GitHub's representations this service reads. Keeping them apart from the domain model
 * is what stops upstream field changes from reaching the published contract
 */
internal data class GithubRepositoryPayload(
    val name: String,
    val fork: Boolean,
    val owner: Owner,
) {
    internal data class Owner(val login: String)
}

internal data class GithubBranchPayload(
    val name: String,
    val commit: Commit,
) {
    internal data class Commit(val sha: String)
}
