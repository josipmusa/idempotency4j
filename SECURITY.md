# Security Policy

## Supported versions

Security fixes go into the latest release. Until 1.0.0, every minor release may change public API
and storage layout, so a fix is published as a new release rather than backported to an earlier
one. Once 1.0.0 is out, the latest minor of the current major line receives fixes.

## Reporting a vulnerability

Please do not report security vulnerabilities through public GitHub issues.

Report vulnerabilities by opening a
[GitHub Security Advisory](https://github.com/josipmusa/idempotency4j/security/advisories/new).
This keeps the details private until a fix is available.

Include as much of the following as you can:

- A description of the vulnerability and its potential impact
- The affected module(s) and version(s)
- The storage backend involved, if it matters (JDBC, Redis, in-memory)
- Steps to reproduce or a proof-of-concept
- Any suggested mitigations

You will receive a response within 7 days. If you do not hear back, follow up on the advisory
thread. Fixed vulnerabilities are announced in the advisory and in `CHANGELOG.md` under `Security`.

## What the library stores

The store persists whatever an adapter hands it, for as long as the record's TTL. Over HTTP that is
the full response of every annotated endpoint, status, headers and body, including 4xx and 5xx
responses. For an annotated method it is whatever the method's `PayloadCodec` encodes. Depending on
the endpoint that may include personal data, tokens or financial data, so:

- Enable encryption at rest on the backing database or Redis instance.
- Keep TTLs short and let the purge job run, so retention is bounded.
- Register a `ResponseSanitizer` bean to strip or redact response headers and body fields before
  they are written. The Spring Boot starter replaces its no-op default with your bean and applies
  it inside `StoredResponseCodec` on every stored response. See the README for an example.
- Audit which methods and endpoints are annotated and what their results contain.

## Keys, scopes and fingerprints

- An idempotency key is client-controlled. Within a scope, any caller that presents the same key
  and, when a fingerprint is stored, the same request body, receives the stored result. The library
  has no notion of tenant or user, so where callers must not be able to replay each other's results,
  prefix the key at the application level (`userId:clientKey`) or make it unguessable (a UUID).
- Keys may carry identifying data, so the library never writes one to a log line or an exception
  message. A record is rendered as its scope followed by a short SHA-256 digest of the key.
- The HTTP adapter's request fingerprint is a SHA-256 hex digest of the request body, stored
  alongside the record. It reveals nothing about the body but does let two requests be compared.
- Request bodies are read into memory up to `idempotency.web.max-body-bytes` (1 MiB by default) so
  they can be fingerprinted; a larger body is rejected with 413 before the handler runs.

## Storage backends

**JDBC.** The starter never issues DDL against a non-embedded database unless
`idempotency.jdbc.initialize-schema=always` is set. Grant the application account only what the
store needs on `idempotency_records`: `SELECT`, `INSERT`, `UPDATE` and `DELETE`. Only a deployment
that lets the store create the table needs DDL rights.

**Redis.** Use Redis 7 or newer, TLS for remote connections, and an ACL restricted to the configured
key prefix and the documented command set. Prefer a dedicated Redis deployment over an existing
evicting cache, and configure `maxmemory-policy noeviction`: eviction of a live idempotency record
can permit the protected action to execute again. Enable persistence appropriate to the required
recovery objective and remember that Sentinel failover uses asynchronous replication; Redis `WAIT`
can reduce but cannot eliminate acknowledged-write loss. Each record carries an ownership and format
marker, and the provider preserves foreign data when namespaces collide.

**In-memory.** Deduplicates within one JVM only and forgets on restart. Not a security boundary of
any kind; it is for tests and local development.

## What the library does not guarantee

The library makes your own work safe to retry. It is not an exactly-once guarantee for downstream
side effects: lease fencing protects the idempotency record, not a third-party call made just
before the process died. Pass an idempotency key to the downstream service, or use a transactional
outbox, for that.

## Out of scope

- Vulnerabilities in third-party dependencies (report those to the respective projects)
- Issues that require physical access to the server, database or Redis instance
- Deployments that ignore the storage guidance above, such as an evicting Redis
