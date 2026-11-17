package com.ticketing.catalog.config;

import com.ticketing.catalog.usecase.CatalogLimits;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

@Configuration
@EnableConfigurationProperties(CatalogLimitsProperties.class)
@ComponentScan(basePackages = "com.ticketing.catalog.usecase", includeFilters = {
    @ComponentScan.Filter(type = FilterType.REGEX, pattern = "^.+UseCase$") }, useDefaultFilters = false)
public class UseCasesConfig {

    @Bean
    CatalogLimits catalogLimits(CatalogLimitsProperties properties) {
        return properties.toDomain();
    }
}
