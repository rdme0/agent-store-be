-- CreateSchema
CREATE SCHEMA IF NOT EXISTS "public";

-- CreateEnum
CREATE TYPE "AgentVersionStatus" AS ENUM ('DRAFT', 'ACTIVE', 'DISABLED');

-- CreateTable
CREATE TABLE "users"
(
    "id"          UUID         NOT NULL,
    "external_id" TEXT         NOT NULL,
    "created_at"  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at"  TIMESTAMP(3) NOT NULL,
    CONSTRAINT "users_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "developers"
(
    "id"           UUID         NOT NULL,
    "user_id"      UUID         NOT NULL,
    "display_name" TEXT         NOT NULL,
    "created_at"   TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at"   TIMESTAMP(3) NOT NULL,
    CONSTRAINT "developers_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "agents"
(
    "id"           UUID         NOT NULL,
    "developer_id" UUID         NOT NULL,
    "slug"         TEXT         NOT NULL,
    "name"         TEXT         NOT NULL,
    "description"  TEXT         NOT NULL,
    "created_at"   TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at"   TIMESTAMP(3) NOT NULL,
    CONSTRAINT "agents_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "agent_versions"
(
    "id"           UUID                 NOT NULL,
    "agent_id"     UUID                 NOT NULL,
    "semver"       TEXT                 NOT NULL,
    "status"       "AgentVersionStatus" NOT NULL DEFAULT 'DRAFT',
    "endpoint"     TEXT                 NOT NULL,
    "price_atomic" BIGINT               NOT NULL,
    "network"      TEXT                 NOT NULL,
    "asset"        TEXT                 NOT NULL,
    "pay_to"       TEXT                 NOT NULL,
    "created_at"   TIMESTAMP(3)         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at"   TIMESTAMP(3)         NOT NULL,
    CONSTRAINT "agent_versions_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "users_external_id_key" ON "users" ("external_id");
CREATE UNIQUE INDEX "developers_user_id_key" ON "developers" ("user_id");
CREATE UNIQUE INDEX "agents_slug_key" ON "agents" ("slug");
CREATE INDEX "agents_developer_id_idx" ON "agents" ("developer_id");
CREATE INDEX "agent_versions_agent_id_status_idx" ON "agent_versions" ("agent_id", "status");
CREATE UNIQUE INDEX "agent_versions_agent_id_semver_key" ON "agent_versions" ("agent_id", "semver");

ALTER TABLE "developers"
    ADD CONSTRAINT "developers_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users" ("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "agents"
    ADD CONSTRAINT "agents_developer_id_fkey" FOREIGN KEY ("developer_id") REFERENCES "developers" ("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "agent_versions"
    ADD CONSTRAINT "agent_versions_agent_id_fkey" FOREIGN KEY ("agent_id") REFERENCES "agents" ("id") ON DELETE CASCADE ON UPDATE CASCADE;
