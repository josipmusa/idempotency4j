package io.github.josipmusa.idempotency.springboot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.josipmusa.core.IdempotencyStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class IdempotencyPurgeConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IdempotencyPurgeConfiguration.class));

    @Test
    void When_NoStoreBeanPresent_Expect_PurgeConfigurationNotCreated() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(IdempotencyPurgeConfiguration.class));
    }

    @Test
    void When_StoreBeanPresent_Expect_PurgeConfigurationCreated() {
        contextRunner
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .run(context -> assertThat(context).hasSingleBean(IdempotencyPurgeConfiguration.class));
    }

    @Test
    void When_PurgeDisabled_Expect_PurgeConfigurationNotCreated() {
        contextRunner
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .withPropertyValues("idempotency.purge.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(IdempotencyPurgeConfiguration.class));
    }

    @Test
    void When_PurgeEnabledExplicitly_Expect_PurgeConfigurationCreated() {
        contextRunner
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .withPropertyValues("idempotency.purge.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(IdempotencyPurgeConfiguration.class));
    }

    @Test
    void When_PurgeExpiredCalled_Expect_StorePurgeExpiredInvoked() {
        IdempotencyStore store = mock(IdempotencyStore.class);
        contextRunner
                .withBean(IdempotencyStore.class, () -> store)
                .run(context -> {
                    IdempotencyPurgeConfiguration config = context.getBean(IdempotencyPurgeConfiguration.class);
                    config.purgeExpired();
                    verify(store).purgeExpired();
                });
    }

    @Test
    void When_CustomCronProvided_Expect_PurgeConfigurationCreated() {
        contextRunner
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .withPropertyValues("idempotency.purge.cron=0 0 2 * * *")
                .run(context -> assertThat(context).hasSingleBean(IdempotencyPurgeConfiguration.class));
    }
}
