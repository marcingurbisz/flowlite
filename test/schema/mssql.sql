IF OBJECT_ID('dbo.flowlite_tick', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.flowlite_tick (
        id uniqueidentifier NOT NULL PRIMARY KEY,
        flow_id varchar(128) NOT NULL,
        flow_instance_id uniqueidentifier NOT NULL,
        version bigint NULL
    )
END;

IF NOT EXISTS (
    SELECT 1
    FROM sys.indexes
    WHERE name = 'idx_flowlite_tick_process'
      AND object_id = OBJECT_ID('dbo.flowlite_tick')
)
BEGIN
    CREATE INDEX idx_flowlite_tick_process ON dbo.flowlite_tick(flow_id, flow_instance_id)
END;

IF OBJECT_ID('dbo.pending_event', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.pending_event (
        id uniqueidentifier NOT NULL PRIMARY KEY DEFAULT NEWID(),
        flow_id varchar(128) NOT NULL,
        flow_instance_id uniqueidentifier NOT NULL,
        event_type varchar(256) NOT NULL,
        event_value varchar(256) NOT NULL
    )
END;

IF OBJECT_ID('dbo.flowlite_history', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.flowlite_history (
        id uniqueidentifier NOT NULL PRIMARY KEY DEFAULT NEWID(),
        occurred_at datetime2 NOT NULL,
        flow_id varchar(128) NOT NULL,
        flow_instance_id uniqueidentifier NOT NULL,
        type varchar(64) NOT NULL,
        stage varchar(128) NULL,
        from_stage varchar(128) NULL,
        to_stage varchar(128) NULL,
        from_status varchar(32) NULL,
        to_status varchar(32) NULL,
        event varchar(256) NULL,
        error_type varchar(512) NULL,
        error_message varchar(4000) NULL,
        error_stack_trace varchar(max) NULL
    )
END;

IF NOT EXISTS (
    SELECT 1
    FROM sys.indexes
    WHERE name = 'idx_flowlite_history_instance'
      AND object_id = OBJECT_ID('dbo.flowlite_history')
)
BEGIN
    CREATE INDEX idx_flowlite_history_instance ON dbo.flowlite_history(flow_id, flow_instance_id, occurred_at)
END;

IF OBJECT_ID('dbo.order_confirmation', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.order_confirmation (
        id uniqueidentifier NOT NULL PRIMARY KEY DEFAULT NEWID(),
        version bigint NOT NULL DEFAULT 0,
        stage varchar(128) NOT NULL,
        stage_status varchar(32) NOT NULL DEFAULT 'PENDING',
        order_number varchar(128) NOT NULL,
        confirmation_type varchar(32) NOT NULL,
        customer_name varchar(128) NOT NULL,
        is_removed_from_queue bit NOT NULL,
        is_customer_informed bit NOT NULL,
        confirmation_timestamp varchar(64) NOT NULL
    )
END;

IF OBJECT_ID('dbo.employee_onboarding', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.employee_onboarding (
        id uniqueidentifier NOT NULL PRIMARY KEY DEFAULT NEWID(),
        version bigint NOT NULL DEFAULT 0,
        stage varchar(128) NOT NULL,
        stage_status varchar(32) NOT NULL DEFAULT 'PENDING',
        is_onboarding_automated bit NOT NULL,
        is_contract_signed bit NOT NULL,
        is_executive_role bit NOT NULL,
        is_security_clearance_required bit NOT NULL,
        is_full_onboarding_required bit NOT NULL,
        is_manager_or_director_role bit NOT NULL,
        is_remote_employee bit NOT NULL,
        is_showcase_instance bit NOT NULL,
        user_created_in_system bit NOT NULL,
        employee_activated bit NOT NULL,
        security_clearance_updated bit NOT NULL,
        department_access_set bit NOT NULL,
        documents_generated bit NOT NULL,
        contract_sent_for_signing bit NOT NULL,
        status_updated_in_hr bit NOT NULL
    )
END;