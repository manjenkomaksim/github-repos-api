package com.manzhenko.githubrepos.domain

/**
 * A repository owned by a GitHub user, together with every branch it currently has
 */
data class Repository(
    val name: String,
    val owner: String,
    val branches: List<Branch>,
)

data class Branch(
    val name: String,
    val lastCommitSha: String,
)
