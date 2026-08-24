create type "ProviderScope" as enum ('PINNED', 'ALLOWLIST', 'MARKETPLACE');
create type "ProviderSelectionStrategy" as enum (
    'LOWEST_PRICE',
    'LATEST_VERSION',
    'HIGHEST_RELIABILITY',
    'FASTEST',
    'BALANCED'
);
create type "AgentInvocationOutcome" as enum (
    'SUCCESS',
    'AGENT_HTTP_FAILURE',
    'OUTPUT_FORMAT_INVALID',
    'OUTPUT_SCHEMA_INVALID',
    'PAYMENT_FAILURE',
    'PAYMENT_RECONCILIATION_REQUIRED',
    'PLATFORM_FAILURE'
);

alter table agent_versions add column manifest_content text;
alter table agent_versions add column manifest_sha256 varchar(64);

alter table agent_dependencies add column function_contract_id uuid;
alter table agent_dependencies add column provider_scope "ProviderScope";
alter table agent_dependencies add column selection_strategy "ProviderSelectionStrategy";
alter table agent_dependencies add column min_reliability_percent integer;
alter table agent_dependencies add column max_p95_latency_millis integer;
alter table agent_dependencies add column exploration_percent integer;
alter table agent_dependencies add column reliability_weight integer;
alter table agent_dependencies add column price_weight integer;
alter table agent_dependencies add column speed_weight integer;
alter table agent_dependencies
    add constraint agent_dependencies_function_contract_id_fkey
    foreign key (function_contract_id) references agent_capabilities (id) on delete restrict on update cascade;
create index agent_dependencies_function_contract_id_idx
    on agent_dependencies (function_contract_id);

update agent_dependencies
set function_contract_id = target_capability_id,
    provider_scope = 'MARKETPLACE',
    selection_strategy = case selection_policy
        when 'LOWEST_PRICE' then 'LOWEST_PRICE'::"ProviderSelectionStrategy"
        when 'LATEST_VERSION' then 'LATEST_VERSION'::"ProviderSelectionStrategy"
    end,
    exploration_percent = 0
where target_capability_id is not null;

update agent_dependencies dependency
set function_contract_id = source.capability_id,
    provider_scope = 'PINNED',
    exploration_percent = 0
from (
    select dependency_id, (array_agg(capability_id))[1] as capability_id
    from (
        select dependency.id as dependency_id, version.capability_id
        from agent_dependencies dependency
        join agent_versions version on version.agent_id = dependency.target_agent_id
        where dependency.target_agent_id is not null
          and version.capability_id is not null
    ) candidates
    group by dependency_id
    having count(distinct capability_id) = 1
) source
where dependency.id = source.dependency_id;

alter table agent_dependencies drop constraint agent_dependencies_target_kind_check;
alter table agent_dependencies
    add constraint agent_dependencies_provider_model_check check (
        (
            function_contract_id is null
            and (
                (target_agent_id is not null and target_capability_id is null and selection_policy is null)
                or
                (target_agent_id is null and target_capability_id is not null and selection_policy is not null)
            )
        )
        or
        (
            function_contract_id is not null
            and provider_scope is not null
            and exploration_percent between 0 and 20
            and (
                (provider_scope = 'PINNED' and target_agent_id is not null and selection_strategy is null)
                or
                (provider_scope in ('ALLOWLIST', 'MARKETPLACE') and selection_strategy is not null)
            )
            and (min_reliability_percent is null or min_reliability_percent between 0 and 100)
            and (max_p95_latency_millis is null or max_p95_latency_millis > 0)
            and (
                selection_strategy <> 'BALANCED'
                or (
                    reliability_weight between 0 and 100
                    and price_weight between 0 and 100
                    and speed_weight between 0 and 100
                    and reliability_weight + price_weight + speed_weight = 100
                )
            )
        )
    );

create table agent_dependency_allowed_providers (
    dependency_id uuid not null,
    agent_id uuid not null,
    created_at timestamptz not null default now(),
    constraint agent_dependency_allowed_providers_pkey primary key (dependency_id, agent_id),
    constraint agent_dependency_allowed_providers_dependency_id_fkey
        foreign key (dependency_id) references agent_dependencies (id) on delete cascade,
    constraint agent_dependency_allowed_providers_agent_id_fkey
        foreign key (agent_id) references agents (id) on delete restrict
);

insert into agent_dependency_allowed_providers (dependency_id, agent_id)
select id, target_agent_id
from agent_dependencies
where target_agent_id is not null
on conflict do nothing;

create table agent_invocation_observations (
    id uuid not null,
    execution_step_id uuid not null,
    agent_version_id uuid not null,
    function_contract_id uuid,
    started_at timestamptz not null,
    completed_at timestamptz,
    latency_millis bigint,
    outcome "AgentInvocationOutcome",
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint agent_invocation_observations_pkey primary key (id),
    constraint agent_invocation_observations_execution_step_id_key unique (execution_step_id),
    constraint agent_invocation_observations_execution_step_id_fkey
        foreign key (execution_step_id) references execution_steps (id) on delete restrict,
    constraint agent_invocation_observations_agent_version_id_fkey
        foreign key (agent_version_id) references agent_versions (id) on delete restrict,
    constraint agent_invocation_observations_function_contract_id_fkey
        foreign key (function_contract_id) references agent_capabilities (id) on delete restrict,
    constraint agent_invocation_observations_latency_millis_check
        check (latency_millis is null or latency_millis >= 0)
);
create index agent_invocation_observations_provider_metrics_idx
    on agent_invocation_observations (function_contract_id, agent_version_id, completed_at desc);
