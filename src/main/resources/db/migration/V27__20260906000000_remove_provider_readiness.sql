drop table agent_version_readiness;

alter table agent_versions drop column verification_input;

drop type "AgentVersionReadinessStatus";
