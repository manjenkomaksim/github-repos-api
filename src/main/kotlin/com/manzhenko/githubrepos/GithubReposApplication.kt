package com.manzhenko.githubrepos

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class GithubReposApplication

fun main(args: Array<String>) {
    runApplication<GithubReposApplication>(*args)
}
