CREATE TYPE "PaymentMode" AS ENUM ('SIMULATED', 'X402');
CREATE TYPE "RevenueType" AS ENUM ('DIRECT', 'DEPENDENCY');

ALTER TABLE "payment_attempts"
  ADD COLUMN "payment_mode" "PaymentMode" NOT NULL DEFAULT 'SIMULATED',
  ADD COLUMN "payment_identifier" TEXT;

CREATE TABLE "revenue_entries" (
  "id" UUID NOT NULL,
  "developer_id" UUID NOT NULL,
  "execution_step_id" UUID NOT NULL,
  "payment_attempt_id" UUID NOT NULL,
  "type" "RevenueType" NOT NULL,
  "amount_atomic" BIGINT NOT NULL,
  "payment_mode" "PaymentMode" NOT NULL,
  "transaction_hash" TEXT,
  "payment_identifier" TEXT,
  "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "revenue_entries_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "revenue_entries_payment_attempt_id_key" ON "revenue_entries"("payment_attempt_id");
CREATE UNIQUE INDEX "revenue_entries_transaction_hash_key" ON "revenue_entries"("transaction_hash");
CREATE UNIQUE INDEX "revenue_entries_payment_identifier_key" ON "revenue_entries"("payment_identifier");
CREATE INDEX "revenue_entries_developer_id_created_at_id_idx" ON "revenue_entries"("developer_id", "created_at", "id");
ALTER TABLE "revenue_entries" ADD CONSTRAINT "revenue_entries_developer_id_fkey" FOREIGN KEY ("developer_id") REFERENCES "developers"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "revenue_entries" ADD CONSTRAINT "revenue_entries_execution_step_id_fkey" FOREIGN KEY ("execution_step_id") REFERENCES "execution_steps"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "revenue_entries" ADD CONSTRAINT "revenue_entries_payment_attempt_id_fkey" FOREIGN KEY ("payment_attempt_id") REFERENCES "payment_attempts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
