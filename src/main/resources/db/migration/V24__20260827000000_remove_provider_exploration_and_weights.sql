do $$
begin
    if exists (
        select 1
        from agent_dependencies
        where selection_strategy = 'BALANCED'
    ) then
        raise exception 'cannot remove BALANCED provider selection rows';
    end if;
end $$;

alter table agent_dependencies drop constraint agent_dependencies_provider_model_check;

create type "ProviderSelectionStrategy_next" as enum (
    'LOWEST_PRICE',
    'LATEST_VERSION',
    'HIGHEST_RELIABILITY',
    'FASTEST'
);

alter table agent_dependencies
    alter column selection_strategy type "ProviderSelectionStrategy_next"
    using selection_strategy::text::"ProviderSelectionStrategy_next";

drop type "ProviderSelectionStrategy";
alter type "ProviderSelectionStrategy_next" rename to "ProviderSelectionStrategy";

alter table agent_dependencies
    drop column exploration_percent,
    drop column reliability_weight,
    drop column price_weight,
    drop column speed_weight;

alter table agent_dependencies
    add constraint agent_dependencies_provider_model_check check (
        (
            function_contract_id is null
            and target_agent_id is not null
            and provider_scope is null
            and selection_strategy is null
            and min_reliability_percent is null
            and max_p95_latency_millis is null
        )
        or
        (
            function_contract_id is not null
            and provider_scope is not null
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
        )
    );
