---
name: api-doc-maintainer
description: Keep AgentStore Spring MVC, Springdoc/Scalar, error, and SSE OpenAPI documentation aligned with the public contract.
---

# API Documentation Maintainer

- Treat generated `/openapi.json` as the source of truth.
- Document only implemented HTTP/SSE APIs; hide the runtime callback route.
- Preserve existing paths, methods, status codes, field names, atomic string amounts, error codes, and nullable values.
- JSON endpoints use `application/json`; execution streams use `text/event-stream`.
- Add validation, auth, payment, domain, and server-error responses that the implementation actually returns.
- Never hand-edit generated OpenAPI. Compare normalized output with the TypeScript golden contract and regenerate the FE client only after Spring contract verification.
