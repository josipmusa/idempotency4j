package io.github.josipmusa.idempotency.springboot;

import io.github.josipmusa.core.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.support.CronExpression;

@AutoConfiguration(after = IdempotencyAutoConfiguration.class)
@ConditionalOnBean(IdempotencyStore.class)
@ConditionalOnProperty(prefix = "idempotency.purge", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyPurgeAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPurgeAutoConfiguration.class);

    @Bean
    public SchedulingConfigurer idempotencyPurgeSchedulingConfigurer(
            IdempotencyStore store, IdempotencyProperties properties) {
        String cron = properties.getPurge().getCron();
        try {
            CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid idempotency.purge.cron value: '" + cron + "'");
        }
        return taskRegistrar -> taskRegistrar.addCronTask(
                () -> {
                    try {
                        store.purgeExpired();
                    } catch (Exception e) {
                        log.error("Idempotency purge task failed", e);
                    }
                },
                cron);
    }
}
