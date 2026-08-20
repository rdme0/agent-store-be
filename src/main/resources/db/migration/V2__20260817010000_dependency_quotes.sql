-- CreateTable
CREATE TABLE "agent_dependencies"
(
    "id"                 UUID         NOT NULL,
    "source_version_id"  UUID         NOT NULL,
    "target_agent_id"    UUID         NOT NULL,
    "version_constraint" TEXT         NOT NULL,
    "required"           BOOLEAN      NOT NULL DEFAULT true,
    "max_price_atomic"   BIGINT       NOT NULL,
    "max_calls"          INTEGER      NOT NULL DEFAULT 1,
    "created_at"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at"         TIMESTAMP(3) NOT NULL,
    CONSTRAINT "agent_dependencies_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "execution_quotes"
(
    "id"              UUID         NOT NULL,
    "root_version_id" UUID         NOT NULL,
    "expires_at"      TIMESTAMP(3) NOT NULL,
    "max_cost_atomic" BIGINT       NOT NULL,
    "snapshot"        JSONB        NOT NULL,
    "created_at"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "execution_quotes_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "agent_dependencies_source_version_id_target_agent_id_key" ON "agent_dependencies" ("source_version_id", "target_agent_id");
CREATE INDEX "agent_dependencies_source_version_id_idx" ON "agent_dependencies" ("source_version_id");
CREATE INDEX "agent_dependencies_target_agent_id_idx" ON "agent_dependencies" ("target_agent_id");
CREATE INDEX "execution_quotes_root_version_id_expires_at_idx" ON "execution_quotes" ("root_version_id", "expires_at");

ALTER TABLE "agent_dependencies"
    ADD CONSTRAINT "agent_dependencies_max_price_atomic_non_negative" CHECK ("max_price_atomic" >= 0);
ALTER TABLE "agent_dependencies"
    ADD CONSTRAINT "agent_dependencies_max_calls_range" CHECK ("max_calls" BETWEEN 1 AND 5);

-- AddForeignKey
ALTER TABLE "agent_dependencies"
    ADD CONSTRAINT "agent_dependencies_source_version_id_fkey" FOREIGN KEY ("source_version_id") REFERENCES "agent_versions" ("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "agent_dependencies"
    ADD CONSTRAINT "agent_dependencies_target_agent_id_fkey" FOREIGN KEY ("target_agent_id") REFERENCES "agents" ("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "execution_quotes"
    ADD CONSTRAINT "execution_quotes_root_version_id_fkey" FOREIGN KEY ("root_version_id") REFERENCES "agent_versions" ("id") ON DELETE RESTRICT ON UPDATE CASCADE;
