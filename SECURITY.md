# Security Policy

## Reporting a Vulnerability

Please do not report security vulnerabilities through public GitHub issues.

Report vulnerabilities by opening a [GitHub Security Advisory](https://github.com/josipmusa/idempotency4j/security/advisories/new). This keeps the details private until a fix is available.

Include as much of the following as you can:

- A description of the vulnerability and its potential impact
- The affected module(s) and version(s)
- Steps to reproduce or a proof-of-concept
- Any suggested mitigations

You will receive a response within 7 days. If you do not hear back, follow up on the advisory thread.

## Reducing sensitive data exposure

The store persists full HTTP response bodies. To strip or redact sensitive fields before they are written to the store, register a `ResponseSanitizer` bean. The Spring Boot starter picks it up automatically and calls it on every response before storage. See the README for an example.

## Scope

This library persists full HTTP response bodies, which may include sensitive data depending on which endpoints are annotated with `@Idempotent`. Key areas to consider when assessing impact:

- The contents of stored responses (PII, tokens, financial data)
- The idempotency key header value used as a map key in the store
- The request fingerprint (SHA-256 of the request body)

## Out of scope

- Vulnerabilities in third-party dependencies (report those to the respective projects)
- Issues that require physical access to the server or database
