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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.AbstractPointcutAdvisor;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.lang.Nullable;

/**
 * Applies {@link IdempotentMethodInterceptor} to every {@link Idempotent} method.
 *
 * <p>Matching a method also registers it with the interceptor. Spring consults a pointcut
 * while it builds proxies, so that is startup - which is where a bad annotation should be
 * caught, and where the parsing belongs so the hot path is a map lookup.
 *
 * <p>Spring MVC request mapping handlers are deliberately not matched. An HTTP request
 * carries its own key, so those are the filter's to guard, and each annotation belongs to
 * exactly one adapter.
 */
public class IdempotentAdvisor extends AbstractPointcutAdvisor {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(IdempotentAdvisor.class);

    /**
     * Named rather than imported: this module must not depend on Spring Web.
     */
    private static final String REQUEST_MAPPING_ANNOTATION = "org.springframework.web.bind.annotation.RequestMapping";

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
            if (isRequestMappingHandler(specific)) {
                log.debug(
                        "Leaving @Idempotent {}.{} to the HTTP adapter: it is a request mapping handler, "
                                + "so its key comes from the request header rather than its parameters",
                        resolvedTarget.getSimpleName(),
                        specific.getName());
                return false;
            }
            interceptor.register(annotation, specific, resolvedTarget);
            return true;
        }
    }

    /**
     * Reports whether the method is a Spring MVC request mapping handler.
     *
     * <p>Such a method is the HTTP adapter's to guard, not this advisor's: an HTTP request
     * brings its own {@code Idempotency-Key}, so the annotation there carries no {@code key}
     * expression and the filter reads only the durations and the scope from it. Advising it
     * here would demand a key the endpoint has no use for, and - once one was invented to
     * satisfy the demand - would guard the same call twice, under two different keys.
     *
     * <p>Matched by annotation name rather than by type so that this module keeps its
     * transport neutrality: {@code idempotency-spring} does not depend on Spring Web, and an
     * application without it on the classpath simply never matches.
     */
    private static boolean isRequestMappingHandler(Method method) {
        return MergedAnnotations.from(method, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY)
                .isPresent(REQUEST_MAPPING_ANNOTATION);
    }
}
