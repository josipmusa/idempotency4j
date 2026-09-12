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
import java.lang.reflect.Method;
import java.util.Objects;
import org.aopalliance.aop.Advice;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.AbstractPointcutAdvisor;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.Nullable;

/**
 * Applies {@link IdempotentMethodInterceptor} to every {@link Idempotent} method.
 *
 * <p>Matching a method also registers it with the interceptor. Spring consults a pointcut
 * while it builds proxies, so that is startup - which is where a bad annotation should be
 * caught, and where the parsing belongs so the hot path is a map lookup.
 */
public class IdempotentAdvisor extends AbstractPointcutAdvisor {

    @Serial
    private static final long serialVersionUID = 1L;

    // Both are transient because neither an engine-backed interceptor nor a pointcut is
    // serializable, and an advisor is infrastructure that is built at startup, never restored
    // from a stream. Spring marks the advice on its own AbstractBeanFactoryPointcutAdvisor the
    // same way.
    private final transient IdempotentMethodInterceptor interceptor;
    private final transient Pointcut pointcut;

    /**
     * @param interceptor what runs for a matched method
     */
    public IdempotentAdvisor(IdempotentMethodInterceptor interceptor) {
        this.interceptor = Objects.requireNonNull(interceptor, "interceptor must not be null");
        this.pointcut = new IdempotentPointcut();
    }

    @Override
    public Pointcut getPointcut() {
        return pointcut;
    }

    @Override
    public Advice getAdvice() {
        return interceptor;
    }

    private final class IdempotentPointcut extends StaticMethodMatcherPointcut {

        @Override
        public boolean matches(Method method, @Nullable Class<?> targetClass) {
            Class<?> resolvedTarget = targetClass != null ? targetClass : method.getDeclaringClass();
            // The annotation sits on the implementation; a JDK proxy asks about the interface method.
            Method specific = AopUtils.getMostSpecificMethod(method, resolvedTarget);
            Idempotent annotation = AnnotatedElementUtils.findMergedAnnotation(specific, Idempotent.class);
            if (annotation == null) {
                return false;
            }
            interceptor.register(annotation, specific, resolvedTarget);
            return true;
        }
    }
}
