create table if not exists flowlite_tick (
    id uuid not null primary key,
    flow_id varchar(128) not null,
    flow_instance_id uuid not null,
    version bigint
);

create index if not exists idx_flowlite_tick_process on flowlite_tick(flow_id, flow_instance_id);

create table if not exists pending_event (
    id uuid default random_uuid() primary key,
    flow_id varchar(128) not null,
    flow_instance_id uuid not null,
    event_type varchar(256) not null,
    event_value varchar(256) not null
);

create table if not exists flowlite_history (
    id uuid default random_uuid() primary key,
    occurred_at timestamp not null,
    flow_id varchar(128) not null,
    flow_instance_id uuid not null,
    type varchar(64) not null,
    stage varchar(128),
    from_stage varchar(128),
    to_stage varchar(128),
    from_status varchar(32),
    to_status varchar(32),
    event varchar(256),
    error_type varchar(512),
    error_message varchar(4000),
    error_stack_trace clob
);

create index if not exists idx_flowlite_history_instance on flowlite_history(flow_id, flow_instance_id, occurred_at);

create table if not exists order_confirmation (
    id uuid default random_uuid() primary key,
    version bigint not null default 0,
    stage varchar(128) not null,
    stage_status varchar(32) not null default 'PENDING',
    order_number varchar(128) not null,
    confirmation_type varchar(32) not null,
    customer_name varchar(128) not null,
    is_removed_from_queue boolean not null,
    is_customer_informed boolean not null,
    confirmation_timestamp varchar(64) not null
);

create table if not exists employee_onboarding (
    id uuid default random_uuid() primary key,
    version bigint not null default 0,
    stage varchar(128) not null,
    stage_status varchar(32) not null default 'PENDING',
    is_onboarding_automated boolean not null,
    is_contract_signed boolean not null,
    is_executive_role boolean not null,
    is_security_clearance_required boolean not null,
    is_full_onboarding_required boolean not null,
    is_manager_or_director_role boolean not null,
    is_remote_employee boolean not null,
    is_showcase_instance boolean not null,
    user_created_in_system boolean not null,
    employee_activated boolean not null,
    security_clearance_updated boolean not null,
    department_access_set boolean not null,
    documents_generated boolean not null,
    contract_sent_for_signing boolean not null,
    status_updated_in_hr boolean not null
);