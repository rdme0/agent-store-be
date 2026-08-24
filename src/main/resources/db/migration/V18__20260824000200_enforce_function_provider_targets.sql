update agent_dependencies
set target_capability_id = null,
    selection_policy = null
where function_contract_id is not null
  and provider_scope in ('ALLOWLIST', 'MARKETPLACE');

alter table agent_dependencies drop constraint agent_dependencies_provider_model_check;

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
            and target_capability_id is null
            and selection_policy is null
            and provider_scope is not null
            and exploration_percent between 0 and 20
            and (
                (provider_scope = 'PINNED' and target_agent_id is not null and selection_strategy is null)
                or
                (
                    provider_scope in ('ALLOWLIST', 'MARKETPLACE')
                    and target_agent_id is null
                    and selection_strategy is not null
                )
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
