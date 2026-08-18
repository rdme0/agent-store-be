# AgentStore BE

Kotlin/Spring Boot migration target for AgentStore. The application follows the layered style of `eco-knock-be-central`: Kotlin controllers/services/DTOs/repositories, Java JPA entities and value objects, constructor injection, Flyway migrations, and explicit domain boundaries.

During migration the Spring shadow server uses `18080`. The existing TypeScript API remains the compatibility reference on `8080` until contract, database, runtime, SSE, and FE parity gates pass. The official x402 SDK remains in the Node bridge on `8091`; demo agents remain on `8090`.

```powershell
copy .env.example .env
.\gradlew.bat classes
.\gradlew.bat test
.\gradlew.bat bootRun
```

Never run TypeScript and Spring write paths against the same database during parity testing. Keep private keys and bridge secrets only in `.env`.
