package io.flowlite.test

import java.util.UUID
import javax.sql.DataSource
import org.springframework.beans.factory.BeanRegistrar
import org.springframework.beans.factory.BeanRegistrarDsl
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.data.relational.core.mapping.NamingStrategy
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource

@SpringBootApplication
open class TestApplication

fun testRegistrar(): BeanRegistrar = BeanRegistrarDsl {
    registerBean<NamingStrategy> {
        SnakeCaseNamingStrategy()
    }
    registerBean<DataSource> {
        SingleConnectionDataSource(
            "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
            true,
        )
    }
    registerBean<NamedParameterJdbcTemplate> {
        NamedParameterJdbcTemplate(bean<DataSource>())
    }
}

class SnakeCaseNamingStrategy : NamingStrategy {
    override fun getColumnName(property: RelationalPersistentProperty): String = property.name.toSnakeCase()
    override fun getTableName(type: Class<*>): String = type.simpleName.toSnakeCase()
}

private fun String.toSnakeCase(): String {
    val sb = StringBuilder()
    for (i in this.indices) {
        val c = this[i]
        if (c.isUpperCase()) {
            if (i != 0) sb.append('_')
            sb.append(c.lowercaseChar())
        } else {
            sb.append(c)
        }
    }
    return sb.toString()
        .replace(Regex("_h_r\\b"), "_hr")
}
