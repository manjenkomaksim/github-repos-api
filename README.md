# GitHub repositories API

Lists the repositories a GitHub user owns, forks excluded, with every branch and the last commit on
it. Kotlin 2.4, Spring Boot 4.1 and Spring WebFlux on Java 25, over the public GitHub REST API.

## Build and run

Java 25 is the only prerequisite, and the Gradle wrapper fetches a matching toolchain if the
machine does not have one.

```bash
./gradlew build
```

```bash
./gradlew bootRun
```

The build also produces a runnable jar:

```bash
java -jar build/libs/github-repos-api-1.0.0.jar
```

The service listens on port 8080.

GitHub allows 60 anonymous requests per hour, and one call to this service costs one request per
repository plus paging, so pass a token:

```bash
GITHUB_TOKEN=<token> ./gradlew bootRun
```

A classic token with no scopes, or a fine grained token with read access to public repositories, is
enough. It is sent to GitHub only, and never logged.

In Docker:

```bash
docker build -t github-repos-api .
```

```bash
docker run --rm -p 8080:8080 -e GITHUB_TOKEN=<token> github-repos-api
```

## API

```
GET /api/v1/users/{username}/repositories
Accept: application/json
```

```bash
curl -s http://localhost:8080/api/v1/users/octocat/repositories
```

One object per repository, here cut down to the first of the six `octocat` owns:

```json
[
  {
    "name": "Hello-World",
    "owner": "octocat",
    "branches": [
      { "name": "master", "lastCommitSha": "7fd1a60b01f91b314f59955a4e4d4e80d8edf11d" },
      { "name": "octocat-patch-1", "lastCommitSha": "b1b3f9723831141a31a1a7252a213e216ea76e56" },
      { "name": "test", "lastCommitSha": "b3cbd5bbd7e81436d2eee04537ea2b4c0cad4cdf" }
    ]
  }
]
```

The response is a plain array: the endpoint is a collection resource and there is no metadata to
carry next to it. The `/api/v1` prefix leaves room to change that later.

`owner` is not required by the task. It is there because it makes an entry self-contained once it is
logged, cached or handed on.

### Errors

Every failure uses the same document:

```json
{
  "timestamp": "2026-01-31T10:15:30.123Z",
  "status": 404,
  "code": "USER_NOT_FOUND",
  "message": "GitHub user 'ghost' was not found",
  "path": "/api/v1/users/ghost/repositories"
}
```

`code` is a closed enum and the field to branch on. `message` is for humans and may be reworded.

| Situation                                      | Status | Code                    |
|------------------------------------------------|--------|-------------------------|
| The name cannot be a GitHub login              | 400    | `INVALID_REQUEST`       |
| GitHub does not know the user                  | 404    | `USER_NOT_FOUND`        |
| No endpoint is mapped to the path              | 404    | `RESOURCE_NOT_FOUND`    |
| The method is not supported                    | 405    | `METHOD_NOT_ALLOWED`    |
| The client asked for something other than JSON | 406    | `NOT_ACCEPTABLE`        |
| The GitHub quota is exhausted                  | 429    | `UPSTREAM_RATE_LIMITED` |
| GitHub answered with a failure                 | 502    | `UPSTREAM_ERROR`        |
| GitHub could not be reached, or not in time    | 504    | `UPSTREAM_UNAVAILABLE`  |
| Anything unforeseen                            | 500    | `INTERNAL_ERROR`        |

Behind those choices:

- 502 and 504 instead of 500 for upstream trouble, so a client can tell a broken GitHub from a broken
  service. Only the second one is worth an alert.
- 429 carries `Retry-After`, taken from GitHub's `retry-after` header or derived from
  `x-ratelimit-reset`. The code says the exhausted quota is this service's, not the caller's.
- 400 is decided before any upstream call. GitHub logins are alphanumeric with single inner hyphens
  and at most 39 characters, so anything else cannot exist.
- 500 always carries the same sentence. The cause is logged with its stack trace instead of being
  handed to the client.

RFC 7807 `ProblemDetail` was the alternative. One content type for both success and failure, plus an
enumerated code, was more useful here than its `type` URIs, which need a documentation site behind
them to mean anything.

### Content negotiation

The service produces `application/json` only. A client that accepts it, accepts anything, or states
no preference gets JSON. Anything else gets 406 with the error document above, still as JSON: a body
the client did not ask for is more useful than no body at all.

## Design

### Layering

```
api      HTTP contract: request mapping, response and error documents
domain   model, use case, and the port it depends on
github   GitHub REST API: paging, mapping, failure translation
```

Dependencies point inwards. There is no Spring, WebClient or HTTP type in `domain`, so its beans are
wired in `ApplicationConfiguration` rather than found by a component scan. The mapping in three steps
(GitHub payload, domain model, response document) is what keeps the upstream format out of the
published contract.

`GithubGateway` keeps repositories and branches as separate operations so that the fork filter runs
before any branch is fetched.

Reactor is used directly rather than through coroutines. WebFlux was the requirement, and the
bounded, order preserving fan-out this needs is one call to `flatMapSequential`.

The controller returns `Mono<List<...>>` and not `Flux<...>`. Streaming the array would commit the
response before the last branch listing has arrived, and a failure at that point could only truncate
the JSON instead of setting a status code. The result is small enough to buffer.

### Talking to GitHub

Two endpoints: `/users/{username}/repos` and `/repos/{owner}/{repo}/branches`. One branch request per
repository is unavoidable, GitHub has no bulk endpoint for it, so the fan-out is bounded by
`github.branch-concurrency` (8) instead of Reactor's default of 256.

- Requests pin `X-GitHub-Api-Version: 2026-03-10`, the current version of the two that
  `GET /versions` lists, so a later version cannot change the payload underneath the client.
- Paging follows the `Link` header instead of counting items and building page URLs, which is what
  the GitHub documentation asks clients to do. Links that leave the configured host are ignored:
  requests carry the token, and it has no business travelling elsewhere.
- `per_page=100`, the maximum GitHub accepts, so a 250 repository account costs 3 listing requests
  rather than 9.
- Up to two retries with exponential backoff, for connection failures and 5xx only. A 404 or an
  exhausted quota will not change on a second attempt.
- 3s to connect, 10s for a response.
- The codec buffer is raised to 4 MB for this client. A page of 100 repositories is a few hundred
  kilobytes, well past the 256 KB default.
- A repository deleted or renamed between the two calls answers 404 on its branches. It is reported
  with an empty branch list instead of failing the whole request.

### Configuration

| Property                    | Default                  |
|-----------------------------|--------------------------|
| `github.base-url`           | `https://api.github.com` |
| `github.token`              | `${GITHUB_TOKEN}`        |
| `github.page-size`          | `100`                    |
| `github.branch-concurrency` | `8`                      |
| `github.connect-timeout`    | `3s`                     |
| `github.response-timeout`   | `10s`                    |
| `github.max-response-size`  | `4MB`                    |
| `github.retry.max-retries`  | `2`                      |
| `github.retry.min-backoff`  | `200ms`                  |

## Tests

```bash
./gradlew test
```

Nothing in the suite touches the network.

| Test                              | Kind        | Scope                                                                       |
|-----------------------------------|-------------|-----------------------------------------------------------------------------|
| `LinkHeaderTest`                  | unit        | Parsing of the header that drives paging                                      |
| `UserRepositoriesServiceTest`     | unit        | The use case against a Mockito double of the port: fork filtering, ordering, bounded fan-out, errors |
| `GithubApiClientTest`             | integration | The adapter against WireMock: paging, request headers, retries, timeouts, failures |
| `UserRepositoriesControllerTest`  | slice       | The HTTP contract in `@WebFluxTest`: response shape and every error status    |
| `UserRepositoriesIntegrationTest` | integration | The application on a real port against a stubbed GitHub, OpenAPI included     |

The line between the two is what a test can prove. Fork filtering, ordering and the bound on parallel
branch reads are logic, so they run against a Mockito double of the port, in memory, and assert the
calls made to that double rather than only the values that come back. Paging, request headers and
failure translation only exist over HTTP, so they run against a stubbed server rather than a mocked
`WebClient`, which would only assert that the code calls the methods it calls. GitHub itself is never
contacted: the two integration tests point the client at WireMock through `github.base-url`.

Both sides are covered: repositories with and without branches, an account with none at all, and
every failure the API can answer with.

## API documentation

With the service running:

- `http://localhost:8080/swagger-ui.html`
- `http://localhost:8080/v3/api-docs`

The generated document is committed as [docs/openapi.yaml](docs/openapi.yaml) and refreshed with:

```bash
curl -s http://localhost:8080/v3/api-docs.yaml -o docs/openapi.yaml
```

## Assumptions

- "Repositories belonging to a user" is read as the repositories the account owns, which is what
  `/users/{username}/repos` returns. GitHub serves organisations from the same path, so an
  organisation name works too.
- Only public repositories are reported. Private ones live behind `/user/repos` and a token bound to
  the account, which is a different feature.
- The latest commit identifier of a branch is the SHA its head points at, as reported by the branch
  listing.
- Repositories keep the order GitHub lists them in; the service does not impose one of its own.
- A repository with no branches, and one that disappears between the two calls, comes back with an
  empty branch list.

## Limitations

- The response is not paginated. An organisation with 76 repositories and 1439 branches takes about
  six seconds to answer, one upstream request per repository; an account with thousands of them
  would mean thousands of requests and one very large document. Paging this endpoint, and passing
  that paging upstream, is the next step.
- Nothing is cached, so two calls for the same user cost the quota twice. Conditional requests with
  the `ETag`s GitHub returns would cut both latency and quota use.
- The service itself is unauthenticated and unthrottled: anyone who can reach it can spend the GitHub
  quota it holds.
- The OpenAPI and Swagger UI endpoints are open. In production they belong behind
  `springdoc.api-docs.enabled=false` or an authenticated route.
