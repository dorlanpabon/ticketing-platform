package com.ticketing.catalog.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.ticketing.catalog.model.gateways.CatalogPort;
import com.ticketing.catalog.usecase.CatalogLimits;
import com.ticketing.catalog.usecase.CatalogUseCase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Clock;

class UseCasesConfigTest {

    @Test
    void wiresCatalogUseCaseWithLimitsFromApplicationYaml() throws IOException {
        try (AnnotationConfigApplicationContext context = catalogContext()) {
            context.refresh();

            assertNotNull(context.getBean(CatalogUseCase.class));
            assertEquals(new CatalogLimits(120, 200, 1, 100_000, 1, 100, 50),
                context.getBean(CatalogLimits.class));
        }
    }

    @Test
    void allowsConfigurationOverridesInsideTheContract() throws IOException {
        try (AnnotationConfigApplicationContext context = catalogContext()) {
            TestPropertyValues.of("catalog.limits.max-capacity=25", "catalog.limits.default-page-size=12")
                .applyTo(context);
            context.refresh();

            CatalogLimits limits = context.getBean(CatalogLimits.class);
            assertEquals(25, limits.maxCapacity());
            assertEquals(12, limits.defaultPageSize());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "catalog.limits.max-capacity=100001", "catalog.limits.min-page-size=0",
        "catalog.limits.max-page-size=20" })
    void failsStartupWithInvalidLimits(String property) throws IOException {
        try (AnnotationConfigApplicationContext context = catalogContext()) {
            TestPropertyValues.of(property).applyTo(context);

            BeanCreationException failure = assertThrows(BeanCreationException.class, context::refresh);
            assertInstanceOf(IllegalArgumentException.class, failure.getMostSpecificCause());
        }
    }

    private static AnnotationConfigApplicationContext catalogContext() throws IOException {
        var yaml = new YamlPropertySourceLoader().load("catalog", new ClassPathResource("application.yaml"));
        var context = new AnnotationConfigApplicationContext();
        yaml.forEach(context.getEnvironment().getPropertySources()::addLast);
        context.register(TestConfig.class);
        return context;
    }

    @Configuration
    @Import(UseCasesConfig.class)
    static class TestConfig {

        @Bean
        public CatalogPort catalogPort() {
            return mock(CatalogPort.class);
        }

        @Bean
        public Clock clock() {
            return Clock.systemUTC();
        }
    }
}
