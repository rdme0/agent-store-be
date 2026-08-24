create type "AgentUsageType" as enum ('USER_FACING', 'INTERNAL_COMPONENT');

alter table agents add column usage_type "AgentUsageType";

update agents agent
set usage_type = case
    when exists (
        select 1
        from agent_versions version
        where version.agent_id = agent.id
          and version.status = 'ACTIVE'::"AgentVersionStatus"
          and version.response_format in ('TEXT'::"AgentResponseFormat", 'MARKDOWN'::"AgentResponseFormat", 'STRUCTURED'::"AgentResponseFormat")
    ) then 'USER_FACING'::"AgentUsageType"
    else 'INTERNAL_COMPONENT'::"AgentUsageType"
end;

alter table agents alter column usage_type set not null;
