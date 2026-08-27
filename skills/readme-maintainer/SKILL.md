---
name: readme-maintainer
description: Keep AgentStore Kotlin/Spring native x402, demo-agent, database, and verification instructions synchronized.
---

# README Maintainer

Document the Spring process (`8080`) and the optional independent Go demo-agent (`8090`), profiles,
YAML-owned public configuration, secret-only Spring `.env`, Flyway baseline/migrate behavior,
native x402-only payment, Spring-only hot-wallet secret handling, and exact Gradle/Go verification
commands. Docker Compose interpolation may retain its required `.env` values. The
demo-agent owns its own `.env.example` and README; backend documentation only links to it. Do not
claim Base Sepolia smoke success without funded configuration.

Keep tracked documentation portable and privacy-safe: never include a host-specific absolute
filesystem path or a username/IDE checkout path. Use repository-relative paths or `이 저장소 루트`.
Required network URLs, Docker-internal paths, and protocol/test fixture URIs may remain.
