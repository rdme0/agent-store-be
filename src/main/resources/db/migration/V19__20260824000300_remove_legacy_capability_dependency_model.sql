alter table agent_dependencies drop constraint agent_dependencies_provider_model_check;

drop index agent_dependencies_source_version_target_capability_key;
drop index agent_dependencies_target_capability_id_idx;

alter table agent_dependencies drop constraint agent_dependencies_target_capability_id_fkey;
alter table agent_dependencies drop column target_capability_id;
alter table agent_dependencies drop column selection_policy;

drop type "DependencySelectionPolicy";

create unique index agent_dependencies_source_version_function_pinned_key
    on agent_dependencies (source_version_id, function_contract_id, target_agent_id)
    where function_contract_id is not null and provider_scope = 'PINNED';

create unique index agent_dependencies_source_version_function_scope_key
    on agent_dependencies (source_version_id, function_contract_id, provider_scope)
    where function_contract_id is not null and provider_scope in ('ALLOWLIST', 'MARKETPLACE');

alter table agent_dependencies
    add constraint agent_dependencies_provider_model_check check (
        (
            function_contract_id is null
            and target_agent_id is not null
            and provider_scope is null
            and selection_strategy is null
            and min_reliability_percent is null
            and max_p95_latency_millis is null
            and exploration_percent is null
            and reliability_weight is null
            and price_weight is null
            and speed_weight is null
        )
        or
        (
            function_contract_id is not null
            and provider_scope is not null
            and exploration_percent between 0 and 20
            and (min_reliability_percent is null or min_reliability_percent between 0 and 100)
            and (max_p95_latency_millis is null or max_p95_latency_millis > 0)
            and (
                (
                    provider_scope = 'PINNED'
                    and target_agent_id is not null
                    and selection_strategy is null
                )
                or
                (
                    provider_scope in ('ALLOWLIST', 'MARKETPLACE')
                    and target_agent_id is null
                    and selection_strategy is not null
                )
            )
            and (
                (
                    selection_strategy = 'BALANCED'
                    and reliability_weight between 0 and 100
                    and price_weight between 0 and 100
                    and speed_weight between 0 and 100
                    and reliability_weight + price_weight + speed_weight = 100
                )
                or
                (
                    selection_strategy is distinct from 'BALANCED'
                    and reliability_weight is null
                    and price_weight is null
                    and speed_weight is null
                )
            )
        )
    );
