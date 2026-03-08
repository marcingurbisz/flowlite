package io.flowlite.test

import javax.sql.DataSource
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator

enum class TestDatabaseDialect(
    val schemaResourcePaths: List<String>,
) {
    H2(
        listOf(
            "schema/h2.sql",
            "schema/h2-test-tables.sql",
        ),
    ),
    MSSQL(
        listOf(
            "schema/mssql.sql",
            "schema/mssql-test-tables.sql",
        ),
    ),
}

fun initializeTestSchema(
    dataSource: DataSource,
    dialect: TestDatabaseDialect,
) {
    val populator = ResourceDatabasePopulator()
    dialect.schemaResourcePaths.forEach { resourcePath ->
        populator.addScript(ClassPathResource(resourcePath))
    }
    populator.execute(dataSource)
}