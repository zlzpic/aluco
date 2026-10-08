package com.aluco.server.processing.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * TimescaleDB runtime beans for seam 2 (aluco.store.timeseries=timescale).
 *
 * Second datasource from the custom key aluco.timescale.jdbc-url (ADR-0011);
 * spring.datasource.* (MySQL) is untouched and stays @Primary so every existing
 * bare JdbcTemplate / DataSource injection still resolves to MySQL. The
 * timescale JdbcTemplate is injected by @Qualifier("timescaleJdbc"); the MySQL
 * pool is available as @Qualifier("mysqlJdbc") (the @Primary JdbcTemplate) for
 * store impls that need the device ∪ tombstone primary-key lookup.
 */
@Configuration
@ConditionalOnProperty(name = "aluco.store.timeseries", havingValue = "timescale")
public class TimescaleConfig {

    /**
     * MySQL DataSource, @Primary — the runtime default (spring.datasource.*).
     * Re-declared here because the presence of any custom DataSource bean
     * (timescaleDataSource below) makes DataSourceAutoConfiguration back off,
     * which would leave JPA/schema/Flyway pointing at the second database.
     */
    @Bean
    @Primary
    public DataSource mysqlDataSource(
            @Value("${spring.datasource.url:jdbc:mysql://localhost:3306/aluco}") String url,
            @Value("${spring.datasource.username:root}") String username,
            @Value("${spring.datasource.password:}") String password) {
        return DataSourceBuilder.create()
                .url(url)
                .username(username)
                .password(password)
                .build();
    }

    @Bean
    @Qualifier("timescaleDataSource")
    public DataSource timescaleDataSource(
            @Value("${aluco.timescale.jdbc-url:jdbc:postgresql://localhost:5432/aluco?reWriteBatchedInserts=true}") String url,
            @Value("${aluco.timescale.username:postgres}") String username,
            @Value("${aluco.timescale.password:}") String password) {
        return DataSourceBuilder.create()
                .url(url)
                .username(username)
                .password(password)
                .build();
    }

    /**
     * MySQL JdbcTemplate — @Primary so every bare JdbcTemplate injection keeps
     * resolving to MySQL (today's behavior) even when the timescale template
     * appears on the classpath. Also reachable as @Qualifier("mysqlJdbc").
     */
    @Bean
    @Primary
    public JdbcTemplate mysqlJdbc(@Qualifier("mysqlDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /** Timescale JdbcTemplate (NOT @Primary) — store impls inject by qualifier. */
    @Bean
    public JdbcTemplate timescaleJdbc(@Qualifier("timescaleDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    @Bean(initMethod = "migrate")
    public Flyway timescaleFlyway(@Qualifier("timescaleDataSource") DataSource ds) {
        return Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration-timescale")
                .load();
    }
}