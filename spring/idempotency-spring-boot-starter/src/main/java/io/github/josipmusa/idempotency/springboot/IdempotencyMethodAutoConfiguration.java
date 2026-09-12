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
import io.github.josipmusa.idempotency.spring.IdempotentAdvisor;
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

/**
 * The method-level half of the starter: the advisor that makes {@code @Idempotent} work on an
 * event listener, a {@code @KafkaListener} method, or a plain service method.
 *
 * <p>Active whenever Spring AOP is on the classpath. It needs no transport, so it activates
 * in a batch job or a consumer exactly as it does in a web application. The advisor is applied
 * by the auto-proxy creator {@code AopAutoConfiguration} registers, which is why this runs
 * after it.
 *
 * <p>Joined completion needs the transaction advisor to run <em>outside</em> this one, so the
 * interceptor finds a transaction already active. Both advisors default to
 * {@code Ordered.LOWEST_PRECEDENCE}, which is a tie rather than an order, so an application
 * using {@code completion = "join-transaction"} must break it - give the transaction advisor
 * higher precedence with
 * {@code @EnableTransactionManagement(order = Ordered.HIGHEST_PRECEDENCE)}. The engine fails
 * loudly rather than silently if it does not: a joined context entered without an active
 * transaction is an {@code IllegalStateException}.
 */
@AutoConfiguration(after = {IdempotencyAutoConfiguration.class, AopAutoConfiguration.class})
@ConditionalOnClass({IdempotentAdvisor.class, MethodInterceptor.class})
public class IdempotencyMethodAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(IdempotencyEngine.class)
    IdempotentMethodInterceptor idempotentMethodInterceptor(
            IdempotencyEngine engine, IdempotencyConfig config, ObjectProvider<OutcomeMapper> outcomeMapper) {
        return new IdempotentMethodInterceptor(engine, config, outcomeMapper.getIfAvailable(OutcomeMapper::defaults));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(IdempotentMethodInterceptor.class)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    IdempotentAdvisor idempotentAdvisor(IdempotentMethodInterceptor interceptor) {
        return new IdempotentAdvisor(interceptor);
    }
}
