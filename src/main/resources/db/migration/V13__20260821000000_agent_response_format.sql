DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'AgentResponseFormat') THEN
        CREATE TYPE "AgentResponseFormat" AS ENUM ('TEXT', 'MARKDOWN', 'STRUCTURED', 'JSON');
    END IF;
END
$$;

ALTER TABLE agent_versions
    ADD COLUMN IF NOT EXISTS response_format "AgentResponseFormat" NOT NULL DEFAULT 'JSON';
