package com.clucky.cartorderservice.config;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.init.ScriptUtils;

@Configuration(proxyBeanMethods = false)
class FlywayCompatibilityConfig {
    @Bean
    FlywayMigrationStrategy orderFlywayMigrationStrategy() {
        return flyway -> {
            var compatibility = new ResourceDatabasePopulator(new ClassPathResource("db/compatibility/legacy-order-v25.sql"));
            compatibility.setSeparator(ScriptUtils.EOF_STATEMENT_SEPARATOR);
            compatibility.execute(flyway.getConfiguration().getDataSource());
            flyway.migrate();
        };
    }
}
