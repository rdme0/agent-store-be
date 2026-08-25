ALTER TABLE "agents"
    RENAME COLUMN "slug" TO "code";

ALTER INDEX "agents_slug_key"
    RENAME TO "agents_code_key";
