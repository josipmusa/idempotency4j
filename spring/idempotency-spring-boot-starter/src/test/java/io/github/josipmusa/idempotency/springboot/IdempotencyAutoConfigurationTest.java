package io.github.josipmusa.idempotency.springboot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.josipmusa.core.IdempotencyConfig;
import io.github.josipmusa.core.IdempotencyEngine;
import io.github.josipmusa.core.IdempotencyStore;
import io.github.josipmusa.idempotency.spring.IdempotencyFilter;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class IdempotencyAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IdempotencyAutoConfiguration.class))
            .withBean(RequestMappingHandlerMapping.class, () -> mock(RequestMappingHandlerMapping.class));

    @Test
    void noStoreBeanPresent_engineAndFilterNotCreated() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(IdempotencyEngine.class);
            assertThat(context).doesNotHaveBean(IdempotencyFilter.class);
            assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
        });
    }

    @Test
    void storeBeanPresent_engineAndFilterCreated() {
        contextRunner
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(IdempotencyEngine.class);
                    assertThat(context).hasSingleBean(IdempotencyFilter.class);
                    assertThat(context).hasSingleBean(FilterRegistrationBean.class);
                });
    }

    @Test
    void customEngineBeanPresent_autoConfiguredEngineSkipped() {
        IdempotencyEngine customEngine = mock(IdempotencyEngine.class);
        contextRunner
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .withBean(IdempotencyEngine.class, () -> customEngine)
                .run(context -> {
                    assertThat(context).hasSingleBean(IdempotencyEngine.class);
                    assertThat(context.getBean(IdempotencyEngine.class)).isSameAs(customEngine);
                });
    }

    @Test
    void customConfigBeanPresent_autoConfiguredConfigSkipped() {
        IdempotencyConfig customConfig =
                IdempotencyConfig.builder().keyHeader("X-Custom-Key").build();
        contextRunner.withBean(IdempotencyConfig.class, () -> customConfig).run(context -> {
            assertThat(context).hasSingleBean(IdempotencyConfig.class);
            assertThat(context.getBean(IdempotencyConfig.class)).isSameAs(customConfig);
        });
    }

    @Test
    void customFilterBeanPresent_autoConfiguredFilterSkipped() {
        contextRunner
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .withBean(IdempotencyFilter.class, () -> mock(IdempotencyFilter.class))
                .run(context -> assertThat(context).hasSingleBean(IdempotencyFilter.class));
    }

    @Test
    void defaultProperties_appliedToConfig() {
        contextRunner.run(context -> {
            IdempotencyConfig config = context.getBean(IdempotencyConfig.class);
            assertThat(config.keyHeader()).isEqualTo("Idempotency-Key");
            assertThat(config.defaultTtl()).isEqualTo(Duration.ofHours(24));
            assertThat(config.defaultLockTimeout()).isEqualTo(Duration.ofSeconds(10));
        });
    }

    @Test
    void customProperties_appliedToConfig() {
        contextRunner
                .withPropertyValues(
                        "idempotency.key-header=X-Request-Id",
                        "idempotency.default-ttl=PT2H",
                        "idempotency.default-lock-timeout=PT30S")
                .run(context -> {
                    IdempotencyConfig config = context.getBean(IdempotencyConfig.class);
                    assertThat(config.keyHeader()).isEqualTo("X-Request-Id");
                    assertThat(config.defaultTtl()).isEqualTo(Duration.ofHours(2));
                    assertThat(config.defaultLockTimeout()).isEqualTo(Duration.ofSeconds(30));
                });
    }

    @Test
    void defaultFilterOrder_appliedToRegistration() {
        contextRunner
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .run(context -> {
                    FilterRegistrationBean<?> registration = context.getBean(FilterRegistrationBean.class);
                    assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1);
                });
    }

    @Test
    void customFilterOrder_appliedToRegistration() {
        contextRunner
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .withPropertyValues("idempotency.filter-order=10")
                .run(context -> {
                    FilterRegistrationBean<?> registration = context.getBean(FilterRegistrationBean.class);
                    assertThat(registration.getOrder()).isEqualTo(10);
                });
    }

    @Test
    void nonServletApplication_noBeanCreated() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IdempotencyAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(IdempotencyFilter.class);
                    assertThat(context).doesNotHaveBean(IdempotencyConfig.class);
                });
    }
}
