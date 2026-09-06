DROP TABLE IF EXISTS "external_api_sales";
DROP TABLE IF EXISTS "external_invocation_intents";
DROP TYPE IF EXISTS "ExternalInvocationStatus";

CREATE TABLE "external_invocations"
(
    "id"                  UUID         NOT NULL,
    "execution_id"        UUID         NOT NULL,
    "idempotency_key"     TEXT         NOT NULL,
    "request_hash"        TEXT         NOT NULL,
    "receipt_token_hash"  TEXT         NOT NULL,
    "receipt_expires_at"  TIMESTAMP(3) NOT NULL,
    "created_at"          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at"          TIMESTAMP(3) NOT NULL,
    CONSTRAINT "external_invocations_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "external_invocations_execution_id_fkey"
        FOREIGN KEY ("execution_id") REFERENCES "executions" ("id") ON DELETE RESTRICT ON UPDATE CASCADE
);

CREATE UNIQUE INDEX "external_invocations_execution_id_key"
    ON "external_invocations" ("execution_id");
CREATE UNIQUE INDEX "external_invocations_idempotency_key_key"
    ON "external_invocations" ("idempotency_key");
CREATE UNIQUE INDEX "external_invocations_receipt_token_hash_key"
    ON "external_invocations" ("receipt_token_hash");
