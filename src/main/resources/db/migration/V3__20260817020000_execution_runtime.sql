CREATE TYPE "ExecutionStatus" AS ENUM ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED');
CREATE TYPE "ExecutionStepStatus" AS ENUM ('CREATED', 'PAYMENT_REQUIRED', 'PAYMENT_SETTLED', 'RUNNING', 'COMPLETED', 'FAILED');
CREATE TYPE "PaymentAttemptStatus" AS ENUM ('REQUIRED', 'AUTHORIZED', 'SETTLED', 'FAILED');

CREATE TABLE "executions"
(
    "id"                   UUID              NOT NULL,
    "quote_id"             UUID              NOT NULL,
    "status"               "ExecutionStatus" NOT NULL DEFAULT 'PENDING',
    "max_budget_atomic"    BIGINT            NOT NULL,
    "reserved_cost_atomic" BIGINT            NOT NULL DEFAULT 0,
    "actual_cost_atomic"   BIGINT            NOT NULL DEFAULT 0,
    "failure_code"         TEXT,
    "created_at"           TIMESTAMP(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at"           TIMESTAMP(3)      NOT NULL,
    CONSTRAINT "executions_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "execution_steps"
(
    "id"               UUID                  NOT NULL,
    "execution_id"     UUID                  NOT NULL,
    "parent_step_id"   UUID,
    "agent_version_id" UUID                  NOT NULL,
    "status"           "ExecutionStepStatus" NOT NULL DEFAULT 'CREATED',
    "call_path"        JSONB                 NOT NULL,
    "idempotency_key"  TEXT,
    "cost_atomic"      BIGINT                NOT NULL DEFAULT 0,
    "output"           JSONB,
    "failure_code"     TEXT,
    "created_at"       TIMESTAMP(3)          NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at"       TIMESTAMP(3)          NOT NULL,
    CONSTRAINT "execution_steps_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "execution_events"
(
    "id"           UUID         NOT NULL,
    "execution_id" UUID         NOT NULL,
    "sequence"     INTEGER      NOT NULL,
    "type"         TEXT         NOT NULL,
    "payload"      JSONB        NOT NULL,
    "created_at"   TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "execution_events_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "payment_attempts"
(
    "id"                UUID                   NOT NULL,
    "execution_step_id" UUID                   NOT NULL,
    "status"            "PaymentAttemptStatus" NOT NULL DEFAULT 'REQUIRED',
    "amount_atomic"     BIGINT                 NOT NULL,
    "network"           TEXT                   NOT NULL,
    "asset"             TEXT                   NOT NULL,
    "pay_to"            TEXT                   NOT NULL,
    "transaction_hash"  TEXT,
    "failure_code"      TEXT,
    "created_at"        TIMESTAMP(3)           NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at"        TIMESTAMP(3)           NOT NULL,
    CONSTRAINT "payment_attempts_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "execution_steps_parent_step_id_idempotency_key_key" ON "execution_steps" ("parent_step_id", "idempotency_key");
CREATE INDEX "executions_status_created_at_idx" ON "executions" ("status", "created_at");
CREATE INDEX "executions_quote_id_idx" ON "executions" ("quote_id");
CREATE INDEX "execution_steps_execution_id_created_at_idx" ON "execution_steps" ("execution_id", "created_at");
CREATE INDEX "execution_steps_parent_step_id_idx" ON "execution_steps" ("parent_step_id");
CREATE UNIQUE INDEX "execution_events_execution_id_sequence_key" ON "execution_events" ("execution_id", "sequence");
CREATE INDEX "execution_events_execution_id_sequence_idx" ON "execution_events" ("execution_id", "sequence");
CREATE INDEX "payment_attempts_execution_step_id_created_at_idx" ON "payment_attempts" ("execution_step_id", "created_at");

ALTER TABLE "executions"
    ADD CONSTRAINT "executions_quote_id_fkey" FOREIGN KEY ("quote_id") REFERENCES "execution_quotes" ("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "execution_steps"
    ADD CONSTRAINT "execution_steps_execution_id_fkey" FOREIGN KEY ("execution_id") REFERENCES "executions" ("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "execution_steps"
    ADD CONSTRAINT "execution_steps_parent_step_id_fkey" FOREIGN KEY ("parent_step_id") REFERENCES "execution_steps" ("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "execution_steps"
    ADD CONSTRAINT "execution_steps_agent_version_id_fkey" FOREIGN KEY ("agent_version_id") REFERENCES "agent_versions" ("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "execution_events"
    ADD CONSTRAINT "execution_events_execution_id_fkey" FOREIGN KEY ("execution_id") REFERENCES "executions" ("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "payment_attempts"
    ADD CONSTRAINT "payment_attempts_execution_step_id_fkey" FOREIGN KEY ("execution_step_id") REFERENCES "execution_steps" ("id") ON DELETE CASCADE ON UPDATE CASCADE;
