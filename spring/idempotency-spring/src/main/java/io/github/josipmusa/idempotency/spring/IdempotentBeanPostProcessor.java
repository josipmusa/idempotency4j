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
package io.github.josipmusa.idempotency.spring;

import java.io.Serial;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.aop.framework.autoproxy.AbstractBeanFactoryAwareAdvisingPostProcessor;

/**
 * Applies {@link IdempotentAdvisor} to every bean with an {@link Idempotent} method, behind any
 * advice the bean already has.
 *
 * <p>This is what puts idempotency behind the transaction interceptor. A bean that is already a
 * proxy - a {@code @Transactional} one, say - gets the advisor appended to the end of its chain,
 * so the interceptor always runs inside the transaction, whatever order the transaction advisor
 * was given. Joined completion finds its transaction open, and an autonomous completion sees it
 * and waits for the commit. A bean with no other advice gets a proxy of its own. For the shape of
 * Spring Modulith's {@code @ApplicationModuleListener} the chain is {@code @Async} (which puts
 * itself first), then the transaction, then idempotency, then the method.
 *
 * <p>The interceptor is supplied lazily and resolved only when a bean with an {@code @Idempotent}
 * method is found. A post-processor is created before ordinary beans, so holding the interceptor
 * eagerly would drag the engine, the store and its {@code DataSource} along with it, and every
 * post-processor registered after this one would miss them.
 *
 * <p>Like {@code @Async}, this cannot advise a bean that is part of a circular reference: the
 * bean is injected into its cycle before it is post-processed, so that reference is to the raw
 * bean. Spring Boot forbids circular references by default.
 */
public class IdempotentBeanPostProcessor extends AbstractBeanFactoryAwareAdvisingPostProcessor {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param interceptor supplies what runs for an {@code @Idempotent} method; called at most
     *                    once, when the first such method is found
     */
    public IdempotentBeanPostProcessor(Supplier<IdempotentMethodInterceptor> interceptor) {
        // beforeExistingAdvisors stays false: appending is what lands the advisor inside the
        // transaction advice rather than around it.
        this.advisor = new IdempotentAdvisor(Objects.requireNonNull(interceptor, "interceptor must not be null"));
    }
}
