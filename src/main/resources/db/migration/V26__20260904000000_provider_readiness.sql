create type "AgentVersionReadinessStatus" as enum (
    'UNVERIFIED',
    'VERIFYING',
    'VERIFIED',
    'UNAVAILABLE',
    'UNKNOWN'
);

alter table agent_versions add column verification_input jsonb;

create table agent_version_readiness (
    version_id uuid not null,
    status "AgentVersionReadinessStatus" not null default 'UNVERIFIED',
    last_paid_certification_at timestamptz,
    last_preflight_at timestamptz,
    certification_transaction_hash text,
    failure_code varchar(128),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null,
    constraint agent_version_readiness_pkey primary key (version_id),
    constraint agent_version_readiness_version_id_fkey
        foreign key (version_id) references agent_versions (id) on delete cascade on update cascade
);

create index agent_version_readiness_status_idx on agent_version_readiness (status);

insert into agent_version_readiness (version_id, status, created_at, updated_at)
select id, 'UNVERIFIED', now(), now()
from agent_versions;
