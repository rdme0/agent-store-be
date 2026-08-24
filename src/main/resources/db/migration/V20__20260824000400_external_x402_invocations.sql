CREATE TYPE "ExternalInvocationStatus" AS ENUM (
    'PAYMENT_PENDING',
    'SETTLING',
    'SETTLED',
    'EXECUTION_CREATED',
    'RECONCILIATION_REQUIRED',
    'FAILED'
);

CREATE TABLE "external_invocation_intents"
(
    "id"                  UUID                       NOT NULL,
    "quote_id"            UUID                       NOT NULL,
    "idempotency_key"     TEXT                       NOT NULL,
    "request_hash"        TEXT                       NOT NULL,
    "receipt_token_hash"  TEXT                       NOT NULL,
    "receipt_expires_at"  TIMESTAMP(3)               NOT NULL,
    "provider_cost_atomic" BIGINT                    NOT NULL,
    "platform_fee_atomic" BIGINT                     NOT NULL,
    "total_cost_atomic"   BIGINT                     NOT NULL,
    "pay_to"              TEXT                       NOT NULL,
    "question"            TEXT,
    "input"               JSONB,
    "status"              "ExternalInvocationStatus" NOT NULL,
    "expires_at"          TIMESTAMP(3)               NOT NULL,
    "payment_fingerprint" TEXT,
    "payer"               TEXT,
    "transaction_hash"    TEXT,
    "execution_id"        UUID,
    "failure_code"        TEXT,
    "created_at"          TIMESTAMP(3)               NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at"          TIMESTAMP(3)               NOT NULL,
    CONSTRAINT "external_invocation_intents_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "external_invocation_intents_cost_check" CHECK (
        provider_cost_atomic > 0
        AND platform_fee_atomic >= 0
        AND total_cost_atomic = provider_cost_atomic + platform_fee_atomic
    )
);

CREATE UNIQUE INDEX "external_invocation_intents_idempotency_key_key"
    ON "external_invocation_intents" ("idempotency_key");
CREATE UNIQUE INDEX "external_invocation_intents_receipt_token_hash_key"
    ON "external_invocation_intents" ("receipt_token_hash");
CREATE UNIQUE INDEX "external_invocation_intents_payment_fingerprint_key"
    ON "external_invocation_intents" ("payment_fingerprint")
    WHERE payment_fingerprint IS NOT NULL;
CREATE UNIQUE INDEX "external_invocation_intents_transaction_hash_key"
    ON "external_invocation_intents" ("transaction_hash")
    WHERE transaction_hash IS NOT NULL;
CREATE UNIQUE INDEX "external_invocation_intents_execution_id_key"
    ON "external_invocation_intents" ("execution_id")
    WHERE execution_id IS NOT NULL;
CREATE INDEX "external_invocation_intents_status_created_at_idx"
    ON "external_invocation_intents" ("status", "created_at");

ALTER TABLE "external_invocation_intents"
    ADD CONSTRAINT "external_invocation_intents_quote_id_fkey"
        FOREIGN KEY ("quote_id") REFERENCES "execution_quotes" ("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "external_invocation_intents"
    ADD CONSTRAINT "external_invocation_intents_execution_id_fkey"
        FOREIGN KEY ("execution_id") REFERENCES "executions" ("id") ON DELETE RESTRICT ON UPDATE CASCADE;

CREATE TABLE "external_api_sales"
(
    "id"                   UUID      NOT NULL,
    "external_intent_id"   UUID      NOT NULL,
    "provider_cost_atomic" BIGINT    NOT NULL,
    "platform_fee_atomic" BIGINT    NOT NULL,
    "total_cost_atomic"    BIGINT    NOT NULL,
    "payer"                TEXT      NOT NULL,
    "transaction_hash"     TEXT      NOT NULL,
    "created_at"           TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at"           TIMESTAMP(3) NOT NULL,
    CONSTRAINT "external_api_sales_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "external_api_sales_cost_check" CHECK (
        provider_cost_atomic > 0
        AND platform_fee_atomic >= 0
        AND total_cost_atomic = provider_cost_atomic + platform_fee_atomic
    )
);

CREATE UNIQUE INDEX "external_api_sales_external_intent_id_key"
    ON "external_api_sales" ("external_intent_id");
CREATE UNIQUE INDEX "external_api_sales_transaction_hash_key"
    ON "external_api_sales" ("transaction_hash");

ALTER TABLE "external_api_sales"
    ADD CONSTRAINT "external_api_sales_external_intent_id_fkey"
        FOREIGN KEY ("external_intent_id") REFERENCES "external_invocation_intents" ("id") ON DELETE RESTRICT ON UPDATE CASCADE;
