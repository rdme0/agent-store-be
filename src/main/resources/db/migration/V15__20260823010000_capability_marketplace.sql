create type "DependencySelectionPolicy" as enum ('LOWEST_PRICE', 'LATEST_VERSION');

create table agent_capabilities (
    id uuid not null,
    key varchar(128) not null,
    contract_version varchar(32) not null,
    name varchar(120) not null,
    description varchar(2000) not null,
    response_format "AgentResponseFormat" not null,
    input_schema jsonb not null,
    output_schema jsonb not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint agent_capabilities_pkey primary key (id),
    constraint agent_capabilities_key_contract_version_key unique (key, contract_version)
);

alter table agent_versions add column capability_id uuid;
alter table agent_versions
    add constraint agent_versions_capability_id_fkey
    foreign key (capability_id) references agent_capabilities (id) on delete restrict on update cascade;
create index agent_versions_capability_id_status_idx on agent_versions (capability_id, status);

alter table agent_dependencies alter column target_agent_id drop not null;
alter table agent_dependencies add column target_capability_id uuid;
alter table agent_dependencies add column selection_policy "DependencySelectionPolicy";
alter table agent_dependencies
    add constraint agent_dependencies_target_capability_id_fkey
    foreign key (target_capability_id) references agent_capabilities (id) on delete restrict on update cascade;
alter table agent_dependencies
    add constraint agent_dependencies_target_kind_check check (
        (target_agent_id is not null and target_capability_id is null and selection_policy is null)
        or
        (target_agent_id is null and target_capability_id is not null and selection_policy is not null)
    );

drop index agent_dependencies_source_version_id_target_agent_id_key;
create unique index agent_dependencies_source_version_target_agent_key
    on agent_dependencies (source_version_id, target_agent_id)
    where target_agent_id is not null;
create unique index agent_dependencies_source_version_target_capability_key
    on agent_dependencies (source_version_id, target_capability_id)
    where target_capability_id is not null;
create index agent_dependencies_target_capability_id_idx on agent_dependencies (target_capability_id);
