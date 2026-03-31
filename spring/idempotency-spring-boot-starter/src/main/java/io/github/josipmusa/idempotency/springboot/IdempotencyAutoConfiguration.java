package io.github.josipmusa.idempotency.springboot;

import io.github.josipmusa.core.IdempotencyConfig;
import io.github.josipmusa.core.IdempotencyEngine;
import io.github.josipmusa.core.IdempotencyStore;
import io.github.josipmusa.idempotency.spring.IdempotencyFilter;
import io.github.josipmusa.idempotency.spring.IdempotentHandlerRegistry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({IdempotencyFilter.class, RequestMappingHandlerMapping.class})
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    IdempotencyConfig idempotencyConfig(IdempotencyProperties idempotencyProperties) {
        return IdempotencyConfig.builder()
                .keyHeader(idempotencyProperties.getKeyHeader())
                .keyRequired(idempotencyProperties.isKeyRequired())
                .defaultTtl(idempotencyProperties.getDefaultTtl())
                .defaultLockTimeout(idempotencyProperties.getDefaultLockTimeout())
                .build();
    }

    @Bean(destroyMethod = "shutdownNow")
    @ConditionalOnMissingBean(name = "idempotencyScheduler")
    public ScheduledExecutorService idempotencyScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "idempotency-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(IdempotencyStore.class)
    IdempotencyEngine idempotencyEngine(
            IdempotencyStore idempotencyStore, ScheduledExecutorService idempotencyScheduler) {
        return new IdempotencyEngine(idempotencyStore, idempotencyScheduler);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotentHandlerRegistry idempotentHandlerRegistry(
            RequestMappingHandlerMapping handlerMapping, IdempotencyConfig config) {
        return new IdempotentHandlerRegistry(handlerMapping, config);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({IdempotencyEngine.class, IdempotencyStore.class})
    public IdempotencyFilter idempotencyFilter(
            IdempotencyEngine engine,
            IdempotencyStore store,
            IdempotencyConfig config,
            RequestMappingHandlerMapping handlerMapping,
            IdempotentHandlerRegistry registry) {
        return new IdempotencyFilter(engine, store, config, handlerMapping, registry);
    }

    @Bean
    @ConditionalOnBean(IdempotencyFilter.class)
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(
            IdempotencyFilter idempotencyFilter, IdempotencyProperties properties) {
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(idempotencyFilter);
        registration.setOrder(properties.getFilterOrder());
        registration.addUrlPatterns("/*");
        return registration;
    }
}
