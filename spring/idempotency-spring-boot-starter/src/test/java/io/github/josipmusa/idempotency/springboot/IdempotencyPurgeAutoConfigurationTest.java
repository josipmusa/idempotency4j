package io.github.josipmusa.idempotency.springboot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.josipmusa.core.IdempotencyStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

class IdempotencyPurgeAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IdempotencyPurgeAutoConfiguration.class));

    @Test
    void When_StoreBeanPresent_Expect_PurgeConfigurerCreatedByDefault() {
        contextRunner
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .run(context -> assertThat(context).hasSingleBean(SchedulingConfigurer.class));
    }

    @Test
    void When_PurgeDisabled_Expect_PurgeConfigurerNotCreated() {
        contextRunner
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .withPropertyValues("idempotency.purge.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(SchedulingConfigurer.class));
    }

    @Test
    void When_NoStoreBeanPresent_Expect_PurgeConfigurerNotCreated() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(SchedulingConfigurer.class));
    }

    @Test
    void When_CustomCronExpression_Expect_BeanCreatedWithCustomCron() {
        contextRunner
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .withPropertyValues("idempotency.purge.cron=0 0 12 * * *")
                .run(context -> {
                    assertThat(context).hasSingleBean(SchedulingConfigurer.class);
                    assertThat(context.getBean(IdempotencyProperties.class)
                                    .getPurge()
                                    .getCron())
                            .isEqualTo("0 0 12 * * *");
                });
    }

    @Test
    void When_InvalidCronExpression_Expect_ContextStartupFails() {
        contextRunner
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .withPropertyValues("idempotency.purge.cron=not-a-cron")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasRootCauseMessage("Invalid idempotency.purge.cron value: 'not-a-cron'");
                });
    }

    @Test
    void When_PurgeExpiredThrows_Expect_ExceptionSwallowed() {
        IdempotencyStore throwingStore = mock(IdempotencyStore.class);
        doThrow(new RuntimeException("DB connection lost")).when(throwingStore).purgeExpired();

        contextRunner.withBean(IdempotencyStore.class, () -> throwingStore).run(context -> {
            SchedulingConfigurer configurer = context.getBean(SchedulingConfigurer.class);
            ScheduledTaskRegistrar registrar = mock(ScheduledTaskRegistrar.class);
            configurer.configureTasks(registrar);

            ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
            verify(registrar).addCronTask(taskCaptor.capture(), anyString());

            assertThatCode(() -> taskCaptor.getValue().run()).doesNotThrowAnyException();
        });
    }
}
