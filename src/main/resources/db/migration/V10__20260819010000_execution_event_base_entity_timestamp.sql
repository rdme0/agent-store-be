-- execution_events also inherits BaseEntity; preserve the immutable Prisma layout
-- while satisfying Hibernate auditing validation for every mapped entity.
ALTER TABLE "execution_events"
    ADD COLUMN "updated_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP;
