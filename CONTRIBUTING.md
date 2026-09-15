# Contributing

Issues and pull requests are welcome. For anything beyond a small fix, open an issue first so the
design can be agreed before the work is done.

## Building the project

Requires Java 21 (see `.sdkmanrc`), Maven through the wrapper, and Docker: the JDBC and Redis
provider tests run against real PostgreSQL, MySQL and Redis containers through
[Testcontainers](https://testcontainers.com/).

```bash
./mvnw spotless:apply   # auto-fix formatting and license headers
./mvnw verify           # compile, all tests, Spotless check
```

`verify` is the same command CI runs. If it passes locally, it passes in CI.

To test one module, pass `-pl` together with `-am` so upstream SNAPSHOT modules are built from
source:

```bash
./mvnw test -pl idempotency-core -am
./mvnw test -pl providers/idempotency-jdbc -am                        # needs Docker
./mvnw test -pl idempotency-core -am -Dtest=IdempotencyEngineTest     # one class
```

Release builds (`./mvnw verify -Prelease -DskipTests -Dgpg.skip=true`) run doclint, so Javadoc on
public API must be well-formed.

## Code style

Formatting is enforced by [Spotless](https://github.com/diffplug/spotless) with
[Palantir Java Format](https://github.com/palantir/palantir-java-format), and license headers are
inserted by `./mvnw spotless:apply`, never by hand. The build fails on any violation.

Tests are named `When_<Context>_Expect_<Result>`.

## Module boundaries

These rules are enforced by design, not by tooling, so a violation is caught in review:

- `idempotency-core` has no framework dependencies, only SLF4J.
- `providers/*` (`idempotency-jdbc`, `idempotency-redis`, `idempotency-inmemory`) depend on core
  only. No Spring.
- `idempotency-test` is core plus JUnit, and holds both store contracts.
- `spring/idempotency-spring` is core plus `spring-context`, `spring-aop` and `spring-tx`, with
  `spring-jdbc` and `idempotency-jdbc` optional for the connection resolver. It is
  transport-neutral: nothing from `jakarta.servlet` may appear in it.
- `spring/idempotency-spring-web` is `idempotency-spring` plus Spring Web MVC.
- `spring/idempotency-spring-boot-starter` requires the two Spring modules and lists every
  provider as optional. It contains autoconfiguration only.

If you find yourself adding a Spring dependency to core or a provider, or a Servlet type to
`idempotency-spring`, the design is wrong.

## Adding a store implementation

1. Implement `IdempotencyStore` from `idempotency-core`.
2. Extend `IdempotencyStoreContract` from `idempotency-test` and implement `store()`. The contract
   is the single source of truth for store behaviour and all of it must pass.
3. If the store reports `supportsTransactionalCompletion()`, also extend
   `TransactionalStoreContract`.

A change to how stores behave goes into the contract first, so every backend is held to it.

## Pull requests

- One logical change per PR.
- Include a test that fails before your change and passes after, unless the change is
  documentation-only.
- Update `CHANGELOG.md` under `Unreleased` for anything a user of the library would notice: a new
  or changed API, a property, a schema or record layout, a behaviour. Describe the net effect
  since the last release. If a later change reworks something already listed under `Unreleased`,
  edit that entry rather than adding a second one, and do not list internal refactors or test
  changes.
- Keep commit messages short and factual, in the imperative: "add H2 dialect", not "added".

Before opening a PR, run `./mvnw spotless:apply` and `./mvnw verify`.
