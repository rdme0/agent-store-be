alter table agent_dependency_allowed_providers
    add column updated_at timestamptz not null default now();
