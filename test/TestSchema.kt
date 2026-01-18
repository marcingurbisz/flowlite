package io.flowlite.test

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import javax.sql.DataSource

fun createScheduledTasksTable(dataSource: DataSource) {
    val jdbc = NamedParameterJdbcTemplate(dataSource)

    jdbc.jdbcTemplate.execute(
        """
        create table if not exists scheduled_tasks (
            task_name varchar(100) not null,
            task_instance varchar(100) not null,
            task_data blob,
            execution_time timestamp not null,
            picked boolean not null,
            picked_by varchar(50),
            last_success timestamp,
            last_failure timestamp,
            consecutive_failures int,
            last_heartbeat timestamp,
            version bigint not null,
            created_at timestamp default current_timestamp not null,
            last_updated_at timestamp default current_timestamp not null,
            primary key (task_name, task_instance)
        );
        """.trimIndent(),
    )

    jdbc.jdbcTemplate.execute(
        """
        create index if not exists idx_scheduled_tasks_execution_time on scheduled_tasks(execution_time);
        """.trimIndent(),
    )
}
