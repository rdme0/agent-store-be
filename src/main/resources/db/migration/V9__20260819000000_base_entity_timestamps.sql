-- BaseEntity is shared by every JPA entity. The Prisma tables for immutable quotes and revenue entries
-- historically omitted updated_at, so add the auditing column before Hibernate validate runs.
ALTER TABLE "execution_quotes"
    ADD COLUMN "updated_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE "revenue_entries"
    ADD COLUMN "updated_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP;
