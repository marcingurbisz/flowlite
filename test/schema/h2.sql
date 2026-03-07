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
    needs_training_program boolean not null,
    is_engineering_role boolean not null,
    is_full_security_setup boolean not null,
    were_documents_signed_physically boolean not null,
    is_not_manual_path boolean not null,
    is_executive_or_management boolean not null,
    has_compliance_checks boolean not null,
    is_not_contractor boolean not null,
    is_remote_employee boolean not null,
    is_manager_or_director_role boolean not null,
    is_showcase_instance boolean not null,
    employee_profile_created boolean not null,
    system_access_activated boolean not null,
    it_business_hours_resolved boolean not null,
    external_accounts_created boolean not null,
    benefits_enrollment_updated boolean not null,
    security_clearance_levels_set boolean not null,
    documents_generated boolean not null,
    contract_sent_for_signing boolean not null,
    removed_from_signing_queue boolean not null,
    delay5_min_completed boolean not null,
    specialized_access_activated boolean not null,
    hr_updated boolean not null,
    delay_after_hr_update_completed boolean not null,
    employee_records_fetched boolean not null,
    department_assignment_updated boolean not null,
    organization_chart_linked boolean not null,
    payroll_status_updated boolean not null,
    onboarding_completed boolean not null
);