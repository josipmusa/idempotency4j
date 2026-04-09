# Contributing

## Building the project

Requires Java 21 and Maven 3.9+.

```bash
mvn verify
```

This compiles everything, runs all tests, and checks formatting. A clean build is the baseline for any contribution.

To test a specific module without running the full suite, always use `-pl` and `-am`:

```bash
mvn test -pl idempotency-core -am
mvn test -pl providers/idempotency-jdbc -am
```

The `-am` flag builds upstream dependencies from source so SNAPSHOT versions resolve correctly.

## Running tests

Unit and integration tests are in each module's `src/test/java`. Provider modules that require infrastructure use [Testcontainers](https://testcontainers.com/) — Docker must be running when testing those.

```bash
mvn test              # all tests
mvn test -pl idempotency-core -am   # core tests only
```

## Code style

Formatting is enforced by [Spotless](https://github.com/diffplug/spotless) with [Palantir Java Format](https://github.com/palantir/palantir-java-format). The build fails on any formatting violation.

To auto-fix before committing:

```bash
mvn spotless:apply
```

## Module boundaries

These rules are enforced by design, not by tooling — violating them will be caught in review:

- `idempotency-core` has zero framework dependencies.
- `idempotency-jdbc` and `idempotency-inmemory` depend on core only — no Spring.
- `idempotency-spring-web` depends on core + Spring Web.
- `idempotency-spring-boot-starter` depends on the spring module + providers.

If you find yourself adding a Spring dependency to core or a provider, stop — the design is wrong.

## Adding a new store implementation

1. Implement `IdempotencyStore` from `idempotency-core`.
2. Extend `IdempotencyStoreContract` from `idempotency-test` and implement `store()`.
3. The contract suite is the single source of truth for store behavior. All tests in it must pass.

## Pull requests

- One logical change per PR.
- Include a test that fails before your change and passes after, unless the change is documentation-only.
- Match existing test naming: `When_<Context>_Expect_<Result>`.
- Keep commit messages short and factual — one sentence per logical change.

Before opening a PR, run:

```bash
mvn verify
```

If it passes locally, it will pass in CI.
