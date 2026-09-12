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

import io.github.josipmusa.idempotency.core.CompletionMode;
import io.github.josipmusa.idempotency.core.IdempotencyConfig;
import io.github.josipmusa.idempotency.core.IdempotencyContext;
import io.github.josipmusa.idempotency.core.IdempotencyEngine;
import io.github.josipmusa.idempotency.core.IdempotencyIdentity;
import io.github.josipmusa.idempotency.core.Outcome;
import io.github.josipmusa.idempotency.core.PayloadCodec;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;

/**
 * Runs an {@link Idempotent} method through the engine instead of calling it directly.
 *
 * <p>The method-level counterpart of the HTTP filter, and the same division of labour: this
 * class resolves the identity and hands the engine a supplier, the engine owns the record,
 * and {@link OutcomeMapper} turns the {@link Outcome} back into a return value. Nothing here
 * knows about a transport, so it works the same for an {@code @EventListener}, a
 * {@code @KafkaListener}, and a plain service method.
 *
 * <p>Where an HTTP request carries its key in a header, a method has to derive one from its
 * arguments, so {@link Idempotent#key()} is a SpEL expression evaluated over them. The scope
 * is the method itself, which keeps two consumers of the same message independent.
 *
 * <p>Wire it with {@link IdempotentAdvisor}, which also gets every annotated method
 * {@link IdempotentOperation resolved and validated} while the proxy is built - so a bad
 * annotation fails the context rather than the first message.
 */
public class IdempotentMethodInterceptor implements MethodInterceptor, BeanFactoryAware {

    private final IdempotencyEngine engine;
    private final IdempotencyConfig config;
    private final OutcomeMapper outcomeMapper;
    private final Map<Method, IdempotentOperation> operations = new ConcurrentHashMap<>();
    private final ParameterNameDiscoverer parameterNames = new DefaultParameterNameDiscoverer();

    private BeanFactory beanFactory;

    /**
     * @param engine the engine every intercepted call runs through
     * @param config the application defaults an annotation may leave unset
     */
    public IdempotentMethodInterceptor(IdempotencyEngine engine, IdempotencyConfig config) {
        this(engine, config, OutcomeMapper.defaults());
    }

    /**
     * @param engine        the engine every intercepted call runs through
     * @param config        the application defaults an annotation may leave unset
     * @param outcomeMapper how an outcome becomes the method's return value
     */
    public IdempotentMethodInterceptor(
            IdempotencyEngine engine, IdempotencyConfig config, OutcomeMapper outcomeMapper) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.outcomeMapper = Objects.requireNonNull(outcomeMapper, "outcomeMapper must not be null");
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    /**
     * Resolves and caches the metadata for an annotated method, failing if the annotation is
     * wrong.
     *
     * <p>Called by {@link IdempotentAdvisor} while proxies are created, which is what makes
     * a malformed {@code @Idempotent} a startup failure.
     *
     * @param annotation  the annotation found on the method
     * @param method      the annotated method
     * @param targetClass the class being proxied, which names the default scope
     * @throws IllegalStateException if the annotation cannot be honoured
     */
    void register(Idempotent annotation, Method method, Class<?> targetClass) {
        IdempotentOperation operation = operations.computeIfAbsent(
                method, ignored -> IdempotentOperation.of(annotation, method, targetClass, config));
        requireStoreSupportsCompletionMode(operation, method);
    }

    /**
     * Fails the context when a method asks for joined completion that its store can never
     * give it.
     *
     * <p>The application-wide {@code idempotency.completion-mode} is checked where the engine
     * is built, but a single {@code @Idempotent(completion = "join-transaction")} never
     * reaches that check: the mode is resolved here, per method. Without this the context
     * starts and the mistake surfaces on the first call, as a complaint that no transaction
     * is active - which is both misleading and as late as it could possibly be.
     */
    private void requireStoreSupportsCompletionMode(IdempotentOperation operation, Method method) {
        if (operation.completionMode() == CompletionMode.JOIN_TRANSACTION
                && !engine.supportsTransactionalCompletion()) {
            throw new IllegalStateException("@Idempotent(completion = \"join-transaction\") on "
                    + method.getDeclaringClass().getSimpleName() + "." + method.getName()
                    + " cannot be honoured: the configured idempotency store cannot complete inside a caller's "
                    + "transaction. Use a store that can, such as the JDBC one, or drop the attribute to complete "
                    + "autonomously.");
        }
    }

    @Nullable
    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = AopUtils.getMostSpecificMethod(invocation.getMethod(), targetClassOf(invocation));
        IdempotentOperation operation = operations.get(method);
        if (operation == null) {
            // Not an annotated method, or the advisor never saw it: leave the call alone.
            return invocation.proceed();
        }

        IdempotencyContext context = contextFor(operation, invocation);
        Outcome<Object> outcome = engine.execute(context, () -> proceed(invocation), codecFor(operation));
        return outcomeMapper.map(outcome, context);
    }

    /**
     * Runs the method body as something the engine can call.
     *
     * <p>{@link MethodInvocation#proceed()} is declared to throw {@link Throwable} while a
     * {@link io.github.josipmusa.idempotency.core.ThrowingSupplier} throws {@link Exception},
     * so the odd remainder - a {@code Throwable} that is neither - is wrapped. Exceptions and
     * errors propagate untouched; the engine releases the lease for all of them alike.
     */
    @Nullable
    private static Object proceed(MethodInvocation invocation) throws Exception {
        try {
            return invocation.proceed();
        } catch (Exception | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new UndeclaredThrowableException(t);
        }
    }

    private IdempotencyContext contextFor(IdempotentOperation operation, MethodInvocation invocation) {
        return IdempotencyContext.builder(new IdempotencyIdentity(operation.scope(), resolveKey(operation, invocation)))
                .ttl(operation.ttl())
                .leaseDuration(operation.lease())
                .waitTimeout(operation.waitTimeout())
                .completionMode(operation.completionMode())
                .build();
    }

    private static Class<?> targetClassOf(MethodInvocation invocation) {
        Object target = invocation.getThis();
        return target != null
                ? ClassUtils.getUserClass(target)
                : invocation.getMethod().getDeclaringClass();
    }

    private String resolveKey(IdempotentOperation operation, MethodInvocation invocation) {
        Object target = invocation.getThis();
        Class<?> targetClass = targetClassOf(invocation);
        EvaluationContext evaluationContext = new MethodBasedEvaluationContext(
                target, invocation.getMethod(), invocation.getArguments(), parameterNames);
        Object resolved = operation.key().getValue(evaluationContext);
        if (resolved == null || resolved.toString().isBlank()) {
            throw new IllegalStateException("@Idempotent key expression resolved to "
                    + (resolved == null ? "null" : "a blank value") + " for " + targetClass.getName() + "#"
                    + invocation.getMethod().getName() + ": every call must produce an idempotency key");
        }
        return resolved.toString();
    }

    @SuppressWarnings("unchecked")
    private PayloadCodec<Object> codecFor(IdempotentOperation operation) {
        if (operation.codecBeanName() == null) {
            return (PayloadCodec<Object>) (PayloadCodec<?>) PayloadCodec.none();
        }
        if (beanFactory == null) {
            throw new IllegalStateException("@Idempotent(codec = \"" + operation.codecBeanName()
                    + "\") needs a BeanFactory to resolve the codec from, and none was set on this interceptor");
        }
        return beanFactory.getBean(operation.codecBeanName(), PayloadCodec.class);
    }
}
