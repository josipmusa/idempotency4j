package io.github.josipmusa.idempotency.springboot;

import io.github.josipmusa.core.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
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

    @Bean
    public SmartInitializingSingleton idempotencyPurgeSchedulingWarner(ApplicationContext applicationContext) {
        return () -> {
            String[] schedulingProcessors =
                    applicationContext.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class);
            if (schedulingProcessors.length == 0) {
                log.warn("idempotency.purge.enabled is true but @EnableScheduling was not detected. "
                        + "Expired idempotency records will NOT be purged automatically. "
                        + "Add @EnableScheduling to your application class, "
                        + "or set idempotency.purge.enabled=false to suppress this warning.");
            }
        };
    }
}
