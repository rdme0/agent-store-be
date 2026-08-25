DO $$
DECLARE
    dependency RECORD;
    parts TEXT[];
    major NUMERIC;
    minor NUMERIC;
    patch NUMERIC;
    converted TEXT;
BEGIN
    FOR dependency IN SELECT id, version_constraint FROM agent_dependencies LOOP
        IF dependency.version_constraint = '*' THEN
            CONTINUE;
        ELSIF dependency.version_constraint ~ '^[0-9]+[.][0-9]+[.][0-9]+$' THEN
            converted := '==' || dependency.version_constraint;
        ELSIF dependency.version_constraint ~ E'^\\^[0-9]+[.][0-9]+[.][0-9]+$' THEN
            parts := regexp_match(substring(dependency.version_constraint from 2), '^([0-9]+)[.]([0-9]+)[.]([0-9]+)$');
            major := parts[1]::NUMERIC;
            minor := parts[2]::NUMERIC;
            patch := parts[3]::NUMERIC;
            converted := '>=' || parts[1] || '.' || parts[2] || '.' || parts[3] || ',';
            IF major > 0 THEN
                converted := converted || '<' || (major + 1) || '.0.0';
            ELSIF minor > 0 THEN
                converted := converted || '<0.' || (minor + 1) || '.0';
            ELSE
                converted := converted || '<0.0.' || (patch + 1);
            END IF;
        ELSIF dependency.version_constraint ~ '^~[0-9]+[.][0-9]+[.][0-9]+$' THEN
            parts := regexp_match(substring(dependency.version_constraint from 2), '^([0-9]+)[.]([0-9]+)[.]([0-9]+)$');
            converted := '>=' || parts[1] || '.' || parts[2] || '.' || parts[3] ||
                ',<' || parts[1] || '.' || (parts[2]::NUMERIC + 1) || '.0';
        ELSE
            RAISE EXCEPTION 'Unsupported legacy version constraint: %', dependency.version_constraint;
        END IF;

        UPDATE agent_dependencies
        SET version_constraint = converted
        WHERE id = dependency.id;
    END LOOP;
END $$;
