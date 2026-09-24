/*
 * Copyright 2026 Josip Musa
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.josipmusa.idempotency.springboot;

import io.github.josipmusa.idempotency.core.IdempotencyConfig;
import io.github.josipmusa.idempotency.core.IdempotencyEngine;
import io.github.josipmusa.idempotency.spring.IdempotentBeanPostProcessor;
import io.github.josipmusa.idempotency.spring.IdempotentMethodInterceptor;
import io.github.josipmusa.idempotency.spring.OutcomeMapper;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;
import org.springframework.core.env.Environment;

/**
 * The method-level half of the starter: the post-processor that makes {@code @Idempotent} work
 * on an event listener, a {@code @KafkaListener} method, or a plain service method.
 *
 * <p>Active whenever Spring AOP is on the classpath. It needs no transport, so it activates
 * in a batch job or a consumer exactly as it does in a web application.
 *
 * <p>{@link IdempotentBeanPostProcessor} appends the idempotency advisor behind whatever advice
 * a bean already has, so the interceptor always runs inside the bean's transaction - joined
 * completion finds it open, and an autonomous completion waits for its commit - with no
 * ordering to configure. It is deliberately not an advisor bean: the auto-proxy creator would
 * apply that one as well, at an order that ties with the transaction advisor's.
 *
 * <p>Proxies follow {@code spring.aop.proxy-target-class}, as the rest of Spring Boot's
 * post-processor-applied advice does.
 */
@AutoConfiguration(after = {IdempotencyAutoConfiguration.class, AopAutoConfiguration.class})
@ConditionalOnClass({IdempotentBeanPostProcessor.class, MethodInterceptor.class})
public class IdempotencyMethodAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(IdempotencyEngine.class)
    IdempotentMethodInterceptor idempotentMethodInterceptor(
            IdempotencyEngine engine, IdempotencyConfig config, ObjectProvider<OutcomeMapper> outcomeMapper) {
        return new IdempotentMethodInterceptor(engine, config, outcomeMapper.getIfAvailable(OutcomeMapper::defaults));
    }

    /**
     * Static, and holding the interceptor only as a provider, because post-processors are
     * created before ordinary beans: anything resolved here eagerly - the interceptor, and with
     * it the engine, the store and the {@code DataSource} - would miss every post-processor
     * registered after this one.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(IdempotentMethodInterceptor.class)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static IdempotentBeanPostProcessor idempotentBeanPostProcessor(
            ObjectProvider<IdempotentMethodInterceptor> interceptor, Environment environment) {
        IdempotentBeanPostProcessor postProcessor = new IdempotentBeanPostProcessor(interceptor::getObject);
        postProcessor.setProxyTargetClass(
                environment.getProperty("spring.aop.proxy-target-class", Boolean.class, Boolean.TRUE));
        return postProcessor;
    }
}
