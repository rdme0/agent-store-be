CREATE TABLE "payment_settlement_journals"
(
    "id"                 UUID         NOT NULL,
    "payment_attempt_id" UUID         NOT NULL,
    "transaction_hash"   TEXT         NOT NULL,
    "created_at"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at"         TIMESTAMP(3) NOT NULL,
    CONSTRAINT "payment_settlement_journals_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "payment_settlement_journals_payment_attempt_id_key" ON "payment_settlement_journals" ("payment_attempt_id");

ALTER TABLE "payment_settlement_journals"
    ADD CONSTRAINT "payment_settlement_journals_payment_attempt_id_fkey" FOREIGN KEY ("payment_attempt_id") REFERENCES "payment_attempts" ("id") ON DELETE CASCADE ON UPDATE CASCADE;
