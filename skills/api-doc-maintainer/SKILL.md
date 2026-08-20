---
name: api-doc-maintainer
description: Keep AgentStore Spring MVC, Springdoc/Scalar, error, and SSE OpenAPI documentation aligned with the public contract.
---

# AgentStore API Documentation Maintainer

- The generated Springdoc artifact at `/openapi.json` is the source of truth for this repository. Never hand-edit it.
- Document only implemented public HTTP/SSE operations. Runtime callback routes remain `@Hidden` and absent from the
  public artifact.
- Preserve existing paths, methods, status codes, operation IDs where already established, JSON field
  names/types/nullability, atomic-string amounts, error codes, and pagination semantics.
- JSON endpoints consume/produce `application/json`; execution streams produce `text/event-stream`, document
  `Last-Event-ID` replay semantics, and retain the raw SSE CORS behavior.
- Add validation, auth, payment, domain, and server-error responses only when the implementation actually returns them.
  Keep the error shape `{ error: { code, message, details? }, traceId }` unchanged.
- Regenerate `/openapi.json` from Springdoc, normalize and compare it semantically with the preserved TypeScript golden
  artifact, then regenerate FE types only after the Spring contract is verified.
