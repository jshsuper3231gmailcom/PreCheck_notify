package com.sks.precheck.notify.config;

import javax.sql.DataSource;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

// test 프로파일: PostgreSQL(로컬), prod 프로파일: Altibase(운영)
@Configuration
public class DataSourceConfig {

    @Bean
    @Profile("test")
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties testDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    @Profile("test")
    public DataSource testDataSource() {
        return testDataSourceProperties().initializeDataSourceBuilder().build();
    }

    @Bean
    @Profile("prod")
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties prodDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    @Profile("prod")
    public DataSource dataSource() {
        return prodDataSourceProperties().initializeDataSourceBuilder().build();
    }
}
