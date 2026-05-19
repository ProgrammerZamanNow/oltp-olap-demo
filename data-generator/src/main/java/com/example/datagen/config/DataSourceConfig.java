package com.example.datagen.config;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Spring Boot DataSource auto-config back off ketika ada DataSource bean user-defined.
 * Karena kita expose ClickHouse sebagai DataSource sekunder, kita harus definisi
 * Postgres primary DataSource secara eksplisit di sini supaya Hibernate JPA tetap
 * pakai Postgres (bukan keliru pakai ClickHouse).
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties primaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties primaryDataSourceProperties) {
        return primaryDataSourceProperties.initializeDataSourceBuilder().build();
    }
}
