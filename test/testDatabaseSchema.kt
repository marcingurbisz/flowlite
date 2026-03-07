package io.flowlite.test

import javax.sql.DataSource
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator

enum class TestDatabaseDialect(
    val schemaResourcePath: String,
) {
    H2("schema/h2.sql"),
    MSSQL("schema/mssql.sql"),
}

fun initializeTestSchema(
    dataSource: DataSource,
    dialect: TestDatabaseDialect,
) {
    ResourceDatabasePopulator(ClassPathResource(dialect.schemaResourcePath)).execute(dataSource)
}