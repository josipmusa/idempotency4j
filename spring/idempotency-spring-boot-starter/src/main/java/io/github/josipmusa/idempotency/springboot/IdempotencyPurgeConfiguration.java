package io.github.josipmusa.idempotency.springboot;

import io.github.josipmusa.core.IdempotencyStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnBean(IdempotencyStore.class)
@ConditionalOnProperty(
        prefix = "idempotency.purge",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class IdempotencyPurgeConfiguration {

    private final IdempotencyStore store;

    public IdempotencyPurgeConfiguration(IdempotencyStore store) {
        this.store = store;
    }

    @Scheduled(cron = "${idempotency.purge.cron:0 0 * * * *}")
    public void purgeExpired() {
        store.purgeExpired();
    }
}
