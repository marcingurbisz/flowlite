package io.flowlite.test

import java.util.UUID
import javax.sql.DataSource
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.relational.core.mapping.NamingStrategy
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource

@SpringBootApplication
open class TestApplication

@Configuration
open class JdbcSnakeCaseConfig {
    @Bean
    open fun namingStrategy(): NamingStrategy = object : NamingStrategy {
        override fun getColumnName(property: RelationalPersistentProperty): String = property.name.toSnakeCase()
        override fun getTableName(type: Class<*>): String = type.simpleName.toSnakeCase()
    }
}

@Configuration
open class TestDataSourceConfig {
    @Bean
    open fun dataSource(): DataSource = SingleConnectionDataSource(
        "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
        true,
    )

    @Bean
    open fun namedParameterJdbcTemplate(dataSource: DataSource): NamedParameterJdbcTemplate =
        NamedParameterJdbcTemplate(dataSource)
}

private fun String.toSnakeCase(): String {
    val sb = StringBuilder()
    for (c in this) {
        if (c.isUpperCase()) sb.append('_').append(c.lowercaseChar()) else sb.append(c)
    }
    return sb.toString()
}
