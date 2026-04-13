IF OBJECT_ID('dbo.flowlite_tick', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.flowlite_tick (
        id uniqueidentifier NOT NULL PRIMARY KEY,
        flow_id varchar(128) NOT NULL,
        flow_instance_id uniqueidentifier NOT NULL,
        not_before datetime2 NOT NULL,
        target_stage varchar(128) NULL,
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

IF NOT EXISTS (
    SELECT 1
    FROM sys.indexes
    WHERE name = 'idx_flowlite_tick_due'
      AND object_id = OBJECT_ID('dbo.flowlite_tick')
)
BEGIN
    CREATE INDEX idx_flowlite_tick_due ON dbo.flowlite_tick(not_before, id)
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

IF NOT EXISTS (
    SELECT 1
    FROM sys.indexes
    WHERE name = 'idx_flowlite_history_summary'
      AND object_id = OBJECT_ID('dbo.flowlite_history')
)
BEGIN
    CREATE INDEX idx_flowlite_history_summary ON dbo.flowlite_history(flow_id, flow_instance_id, type, occurred_at)
END;

IF OBJECT_ID('dbo.flowlite_instance_summary', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.flowlite_instance_summary (
        id uniqueidentifier NOT NULL PRIMARY KEY DEFAULT NEWID(),
        flow_id varchar(128) NOT NULL,
        flow_instance_id uniqueidentifier NOT NULL,
        stage varchar(128) NULL,
        status varchar(32) NULL,
        activity_status varchar(32) NULL,
        last_error_message varchar(4000) NULL,
        updated_at datetime2 NOT NULL
    )
END;

IF COL_LENGTH('dbo.flowlite_instance_summary', 'activity_status') IS NULL
BEGIN
    ALTER TABLE dbo.flowlite_instance_summary ADD activity_status varchar(32) NULL
END;

IF NOT EXISTS (
    SELECT 1
    FROM sys.indexes
    WHERE name = 'idx_flowlite_instance_summary_key'
      AND object_id = OBJECT_ID('dbo.flowlite_instance_summary')
)
BEGIN
    CREATE UNIQUE INDEX idx_flowlite_instance_summary_key ON dbo.flowlite_instance_summary(flow_id, flow_instance_id)
END;

IF NOT EXISTS (
    SELECT 1
    FROM sys.indexes
    WHERE name = 'idx_flowlite_instance_summary_instance'
      AND object_id = OBJECT_ID('dbo.flowlite_instance_summary')
)
BEGIN
    CREATE INDEX idx_flowlite_instance_summary_instance ON dbo.flowlite_instance_summary(flow_instance_id)
END;

IF NOT EXISTS (
    SELECT 1
    FROM sys.indexes
    WHERE name = 'idx_flowlite_instance_summary_updated'
      AND object_id = OBJECT_ID('dbo.flowlite_instance_summary')
)
BEGIN
    CREATE INDEX idx_flowlite_instance_summary_updated ON dbo.flowlite_instance_summary(flow_id, updated_at, flow_instance_id)
END;

IF NOT EXISTS (
    SELECT 1
    FROM sys.indexes
    WHERE name = 'idx_flowlite_instance_summary_status_stage'
      AND object_id = OBJECT_ID('dbo.flowlite_instance_summary')
)
BEGIN
    CREATE INDEX idx_flowlite_instance_summary_status_stage ON dbo.flowlite_instance_summary(flow_id, status, stage, updated_at, flow_instance_id)
END;

IF NOT EXISTS (
    SELECT 1
    FROM sys.indexes
    WHERE name = 'idx_flowlite_instance_summary_activity'
      AND object_id = OBJECT_ID('dbo.flowlite_instance_summary')
)
BEGIN
    CREATE INDEX idx_flowlite_instance_summary_activity ON dbo.flowlite_instance_summary(flow_id, activity_status, updated_at, flow_instance_id)
END;