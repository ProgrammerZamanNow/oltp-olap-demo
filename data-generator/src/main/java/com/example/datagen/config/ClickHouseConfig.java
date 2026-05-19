package com.example.datagen.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class ClickHouseConfig {

    @Bean(name = "clickhouseDataSource")
    public DataSource clickhouseDataSource(
            @Value("${clickhouse.url}") String url,
            @Value("${clickhouse.user}") String user,
            @Value("${clickhouse.password}") String password) {

        var cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(4);
        cfg.setMinimumIdle(1);
        cfg.setPoolName("clickhouse-pool");
        cfg.setConnectionTimeout(5_000);
        return new HikariDataSource(cfg);
    }

    @Bean(name = "clickhouseJdbc")
    public JdbcTemplate clickhouseJdbc(@Qualifier("clickhouseDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
