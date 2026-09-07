package com.manzhenko.githubrepos.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class UserRepositoriesServiceTest {

    private val github = mock<GithubGateway>()
    private val service = UserRepositoriesService(github, BRANCH_CONCURRENCY)

    @Test
    fun `returns every repository of the user with its branches`() {
        givenRepositories(summary("hello-world"), summary("spring-boot"))
        givenBranches("hello-world", Branch("main", "d0e1f2"))
        givenBranches("spring-boot", Branch("main", "a1b2c3"), Branch("3.4.x", "4d5e6f"))

        StepVerifier.create(service.listRepositories(OWNER))
            .expectNext(Repository("hello-world", OWNER, listOf(Branch("main", "d0e1f2"))))
            .expectNext(Repository("spring-boot", OWNER, listOf(Branch("main", "a1b2c3"), Branch("3.4.x", "4d5e6f"))))
            .verifyComplete()

        verify(github).listRepositories(OWNER)
        verify(github).listBranches(OWNER, "hello-world")
        verify(github).listBranches(OWNER, "spring-boot")
        verifyNoMoreInteractions(github)
    }

    @Test
    fun `leaves out forks without looking up their branches`() {
        givenRepositories(summary("hello-world"), summary("linux", isFork = true))
        givenBranches("hello-world", Branch("main", "d0e1f2"))

        StepVerifier.create(service.listRepositories(OWNER))
            .expectNext(Repository("hello-world", OWNER, listOf(Branch("main", "d0e1f2"))))
            .verifyComplete()

        verify(github).listRepositories(OWNER)
        verify(github).listBranches(OWNER, "hello-world")
        verify(github, never()).listBranches(any(), eq("linux"))
        verifyNoMoreInteractions(github)
    }

    @Test
    fun `reports a repository without branches as an empty collection`() {
        givenRepositories(summary("blank"))
        givenBranches("blank")

        StepVerifier.create(service.listRepositories(OWNER))
            .expectNext(Repository("blank", OWNER, emptyList()))
            .verifyComplete()

        verify(github).listRepositories(OWNER)
        verify(github).listBranches(OWNER, "blank")
        verifyNoMoreInteractions(github)
    }

    @Test
    fun `returns nothing when the user owns no repositories`() {
        givenRepositories()

        StepVerifier.create(service.listRepositories(OWNER))
            .verifyComplete()

        verify(github).listRepositories(OWNER)
        verify(github, never()).listBranches(any(), any())
        verifyNoMoreInteractions(github)
    }

    @Test
    fun `keeps the order github listed the repositories in when branches arrive out of order`() {
        givenRepositories(summary("slow"), summary("fast"))
        whenever(github.listBranches(OWNER, "slow"))
            .thenReturn(Flux.just(Branch("main", "d0e1f2")).delayElements(Duration.ofMillis(100)))
        givenBranches("fast", Branch("main", "a1b2c3"))

        StepVerifier.create(service.listRepositories(OWNER).map(Repository::name))
            .expectNext("slow", "fast")
            .verifyComplete()

        verify(github).listRepositories(OWNER)
        verify(github).listBranches(OWNER, "slow")
        verify(github).listBranches(OWNER, "fast")
        verifyNoMoreInteractions(github)
    }

    @Test
    fun `keeps no more branch listings in flight than it is allowed to`() {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        // In flight means asked for and not answered yet, so the count rises on subscription and
        // falls when the branches arrive
        val delayedBranches = Flux.just(Branch("main", "d0e1f2"))
            .delayElements(Duration.ofMillis(50))
            .doOnSubscribe { peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max) }
            .doOnNext { inFlight.decrementAndGet() }
        givenRepositories(*(1..REPOSITORIES).map { summary("repository-$it") }.toTypedArray())
        whenever(github.listBranches(eq(OWNER), any())).thenReturn(delayedBranches)

        StepVerifier.create(service.listRepositories(OWNER))
            .expectNextCount(REPOSITORIES.toLong())
            .verifyComplete()

        assertThat(peak.get())
            .describedAs("branch listings in flight at once, out of %d repositories", REPOSITORIES)
            .isEqualTo(BRANCH_CONCURRENCY)
        verify(github).listRepositories(OWNER)
        verify(github, times(REPOSITORIES)).listBranches(eq(OWNER), any())
        verifyNoMoreInteractions(github)
    }

    @Test
    fun `passes on the failure when the user does not exist`() {
        whenever(github.listRepositories(OWNER)).thenReturn(Flux.error(GithubUserNotFoundException(OWNER)))

        StepVerifier.create(service.listRepositories(OWNER))
            .verifyError(GithubUserNotFoundException::class.java)

        verify(github).listRepositories(OWNER)
        verify(github, never()).listBranches(any(), any())
        verifyNoMoreInteractions(github)
    }

    @Test
    fun `passes on the failure when branches cannot be read`() {
        givenRepositories(summary("hello-world"))
        whenever(github.listBranches(OWNER, "hello-world"))
            .thenReturn(Flux.error(GithubApiException("GitHub API responded with 500", 500)))

        StepVerifier.create(service.listRepositories(OWNER))
            .verifyError(GithubApiException::class.java)

        verify(github).listRepositories(OWNER)
        verify(github).listBranches(OWNER, "hello-world")
        verifyNoMoreInteractions(github)
    }

    private fun givenRepositories(vararg repositories: RepositorySummary) {
        whenever(github.listRepositories(OWNER)).thenReturn(Flux.fromArray(repositories))
    }

    private fun givenBranches(repository: String, vararg branches: Branch) {
        whenever(github.listBranches(OWNER, repository)).thenReturn(Flux.fromArray(branches))
    }

    private fun summary(name: String, isFork: Boolean = false) = RepositorySummary(name, OWNER, isFork)

    private companion object {
        const val OWNER = "octocat"
        const val BRANCH_CONCURRENCY = 4
        const val REPOSITORIES = 12
    }
}
