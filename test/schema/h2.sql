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

create index if not exists idx_flowlite_history_summary on flowlite_history(flow_id, flow_instance_id, type, occurred_at);