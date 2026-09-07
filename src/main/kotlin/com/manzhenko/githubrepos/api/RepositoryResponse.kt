package com.manzhenko.githubrepos.api

import com.manzhenko.githubrepos.domain.Branch
import com.manzhenko.githubrepos.domain.Repository
import io.swagger.v3.oas.annotations.media.Schema

@Schema(name = "Repository", description = "A repository owned by the requested user")
data class RepositoryResponse(
    @field:Schema(description = "Name of the repository, unique within the owning account", example = "hello-world")
    val name: String,
    @field:Schema(description = "Login of the account that owns the repository", example = "octocat")
    val owner: String,
    @field:Schema(description = "Every branch the repository currently has")
    val branches: List<BranchResponse>,
) {
    companion object {
        fun from(repository: Repository) = RepositoryResponse(
            name = repository.name,
            owner = repository.owner,
            branches = repository.branches.map(BranchResponse::from),
        )
    }
}

@Schema(name = "Branch", description = "A branch and the commit it currently points at")
data class BranchResponse(
    @field:Schema(description = "Name of the branch", example = "main")
    val name: String,
    @field:Schema(
        description = "SHA of the last commit on the branch",
        example = "7fd1a60b01f91b314f59955a4e4d4e80d8edf11d",
    )
    val lastCommitSha: String,
) {
    companion object {
        fun from(branch: Branch) = BranchResponse(
            name = branch.name,
            lastCommitSha = branch.lastCommitSha,
        )
    }
}
