# AgentStore BE

Kotlin/Spring Boot migration target for AgentStore. The application follows the layered style of `eco-knock-be-central`:
Kotlin controllers/services/DTOs/repositories, Java JPA entities and value objects, constructor injection, Flyway
migrations, and explicit domain boundaries.

The Spring API runs on `8080`. The official x402 SDK remains in the Node bridge on `8091`; demo agents remain on `8090`.

```powershell
copy .env.example .env
.\gradlew.bat classes
.\gradlew.bat test
.\gradlew.bat bootRun
```

Keep private keys and bridge secrets only in `.env`. The former TypeScript API was used only as the parity reference and
is no longer part of the runtime.
