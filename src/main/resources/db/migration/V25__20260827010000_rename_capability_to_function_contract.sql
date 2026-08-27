alter table agent_capabilities rename to function_contracts;
alter table function_contracts rename column key to code;

alter table agent_versions rename column capability_id to function_contract_id;
alter table agent_versions
    rename constraint agent_versions_capability_id_fkey to agent_versions_function_contract_id_fkey;

alter table function_contracts
    rename constraint agent_capabilities_pkey to function_contracts_pkey;
alter table function_contracts
    rename constraint agent_capabilities_key_contract_version_key
    to function_contracts_code_contract_version_key;
alter index agent_versions_capability_id_status_idx
    rename to agent_versions_function_contract_id_status_idx;
