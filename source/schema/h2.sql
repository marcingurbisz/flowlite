create table if not exists flowlite_tick (
    id uuid not null primary key,
    flow_id varchar(128) not null,
    flow_instance_id uuid not null,
    not_before timestamp not null,
    target_stage varchar(128),
    version bigint
);

create index if not exists idx_flowlite_tick_process on flowlite_tick(flow_id, flow_instance_id);
create index if not exists idx_flowlite_tick_due on flowlite_tick(not_before, id);

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

create index if not exists idx_flowlite_history_summary on flowlite_history(flow_id, flow_instance_id, type, occurred_at);

create table if not exists flowlite_instance_summary (
    id uuid default random_uuid() primary key,
    flow_id varchar(128) not null,
    flow_instance_id uuid not null,
    stage varchar(128),
    status varchar(32),
    activity_status varchar(32),
    last_error_message varchar(4000),
    updated_at timestamp not null
);

alter table flowlite_instance_summary add column if not exists activity_status varchar(32);

create unique index if not exists idx_flowlite_instance_summary_key on flowlite_instance_summary(flow_id, flow_instance_id);
create index if not exists idx_flowlite_instance_summary_updated on flowlite_instance_summary(flow_id, updated_at, flow_instance_id);
create index if not exists idx_flowlite_instance_summary_status_stage on flowlite_instance_summary(flow_id, status, stage, updated_at, flow_instance_id);
create index if not exists idx_flowlite_instance_summary_activity on flowlite_instance_summary(flow_id, activity_status, updated_at, flow_instance_id);