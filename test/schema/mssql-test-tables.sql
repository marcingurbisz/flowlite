IF OBJECT_ID('dbo.order_confirmation', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.order_confirmation (
        id uniqueidentifier NOT NULL PRIMARY KEY DEFAULT NEWID(),
        version bigint NOT NULL DEFAULT 0,
        stage varchar(128) NULL,
        stage_status varchar(32) NULL DEFAULT 'PENDING',
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
        stage varchar(128) NULL,
        stage_status varchar(32) NULL DEFAULT 'PENDING',
        is_onboarding_automated bit NOT NULL,
        needs_training_program bit NOT NULL,
        is_engineering_role bit NOT NULL,
        is_full_security_setup bit NOT NULL,
        were_documents_signed_physically bit NOT NULL,
        is_not_manual_path bit NOT NULL,
        is_executive_or_management bit NOT NULL,
        has_compliance_checks bit NOT NULL,
        is_not_contractor bit NOT NULL,
        is_remote_employee bit NOT NULL,
        is_manager_or_director_role bit NOT NULL,
        is_showcase_instance bit NOT NULL,
        employee_profile_created bit NOT NULL,
        system_access_activated bit NOT NULL,
        external_accounts_created bit NOT NULL,
        benefits_enrollment_updated bit NOT NULL,
        security_clearance_levels_set bit NOT NULL,
        documents_generated bit NOT NULL,
        contract_sent_for_signing bit NOT NULL,
        removed_from_signing_queue bit NOT NULL,
        specialized_access_activated bit NOT NULL,
        hr_updated bit NOT NULL,
        employee_records_fetched bit NOT NULL,
        department_assignment_updated bit NOT NULL,
        organization_chart_linked bit NOT NULL,
        payroll_status_updated bit NOT NULL,
        onboarding_completed bit NOT NULL
    )
END;
