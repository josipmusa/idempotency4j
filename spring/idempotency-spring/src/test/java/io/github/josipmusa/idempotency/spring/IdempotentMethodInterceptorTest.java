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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.josipmusa.idempotency.core.AcquireResult;
import io.github.josipmusa.idempotency.core.IdempotencyConfig;
import io.github.josipmusa.idempotency.core.IdempotencyContext;
import io.github.josipmusa.idempotency.core.IdempotencyEngine;
import io.github.josipmusa.idempotency.core.IdempotencyStore;
import io.github.josipmusa.idempotency.core.Payload;
import io.github.josipmusa.idempotency.core.PayloadCodec;
import io.github.josipmusa.idempotency.inmemory.InMemoryIdempotencyStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class IdempotentMethodInterceptorTest {

    private final IdempotencyStore store = new InMemoryIdempotencyStore();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final IdempotencyEngine engine = new IdempotencyEngine(store, scheduler);
    private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();

    private Recorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new Recorder();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void When_AnnotatedVoidMethodCalledTwiceSameKey_Expect_BodyRunsOnce() {
        Service service = proxy(recorder);

        service.handle("msg-1");
        service.handle("msg-1");

        assertThat(recorder.calls).containsExactly("msg-1");
    }

    @Test
    void When_KeyExpressionUsesParameter_Expect_Resolved() {
        Service service = proxy(recorder);

        service.handle("msg-1");
        service.handle("msg-2");

        assertThat(recorder.calls).containsExactly("msg-1", "msg-2");
        assertThat(store.tryAcquire(contextFor("Recorder.handle", "msg-2")))
                .isInstanceOf(AcquireResult.Duplicate.class);
    }

    @Test
    void When_InFlight_Expect_IdempotencyInFlightExceptionWithRetryAfter() {
        store.tryAcquire(contextFor("Recorder.handle", "msg-held"));
        Service service = proxy(recorder);

        assertThatThrownBy(() -> service.handle("msg-held"))
                .isInstanceOf(IdempotencyInFlightException.class)
                .satisfies(thrown -> assertThat(((IdempotencyInFlightException) thrown).retryAfter())
                        .isBetween(Duration.ZERO, Duration.ofSeconds(30)));
        assertThat(recorder.calls).isEmpty();
    }

    @Test
    void When_ScopeNotSet_Expect_SimpleClassNameAndMethodName() {
        Service service = proxy(recorder);

        service.handle("msg-1");

        assertThat(store.tryAcquire(contextFor("Recorder.handle", "msg-1")))
                .isInstanceOf(AcquireResult.Duplicate.class);
    }

    @Test
    void When_ScopeSetOnAnnotation_Expect_UsedInsteadOfDefault() {
        Service service = proxy(recorder);

        service.handleScoped("msg-1");

        assertThat(store.tryAcquire(contextFor("orders.inbox", "msg-1"))).isInstanceOf(AcquireResult.Duplicate.class);
    }

    @Test
    void When_NonVoidMethodWithCodecBean_Expect_ValueReplayedOnDuplicate() {
        beanFactory.registerSingleton("textCodec", new TextCodec());
        Service service = proxy(recorder);

        String first = service.describe("msg-1");
        String replayed = service.describe("msg-1");

        assertThat(first).isEqualTo("described msg-1");
        assertThat(replayed).isEqualTo("described msg-1");
        assertThat(recorder.calls).containsExactly("msg-1");
    }

    @Test
    void When_NonVoidMethodWithoutCodec_Expect_RejectedAtStartup() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(
                    IdempotentMethodInterceptor.class,
                    () -> new IdempotentMethodInterceptor(engine, IdempotencyConfig.defaults()));
            context.registerBean(
                    IdempotentAdvisor.class,
                    () -> new IdempotentAdvisor(context.getBean(IdempotentMethodInterceptor.class)));
            context.registerBean(DefaultAdvisorAutoProxyCreator.class);
            context.registerBean(MissingCodecRecorder.class);

            assertThatThrownBy(context::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("codec");
        }
    }

    @Test
    void When_BeanIsWellFormed_Expect_ContextStartsAndMethodIsIntercepted() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(
                    IdempotentMethodInterceptor.class,
                    () -> new IdempotentMethodInterceptor(engine, IdempotencyConfig.defaults()));
            context.registerBean(
                    IdempotentAdvisor.class,
                    () -> new IdempotentAdvisor(context.getBean(IdempotentMethodInterceptor.class)));
            context.registerBean(DefaultAdvisorAutoProxyCreator.class);
            context.registerBean(Recorder.class, () -> recorder);
            context.refresh();

            Service service = context.getBean(Service.class);
            service.handle("msg-1");
            service.handle("msg-1");

            assertThat(recorder.calls).containsExactly("msg-1");
        }
    }

    @Test
    void When_KeyExpressionResolvesToBlank_Expect_Rejected() {
        Service service = proxy(recorder);

        assertThatThrownBy(() -> service.handle(" "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key");
        assertThat(recorder.calls).isEmpty();
    }

    @Test
    void When_ActionThrows_Expect_ExceptionPropagatedAndKeyReusable() {
        Service service = proxy(recorder);

        assertThatThrownBy(() -> service.fail("msg-1")).isInstanceOf(IllegalArgumentException.class);

        assertThat(store.tryAcquire(contextFor("Recorder.fail", "msg-1"))).isInstanceOf(AcquireResult.Acquired.class);
    }

    private <T> T proxy(Object target) {
        IdempotentMethodInterceptor interceptor = new IdempotentMethodInterceptor(engine, IdempotencyConfig.defaults());
        interceptor.setBeanFactory(beanFactory);
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvisor(new IdempotentAdvisor(interceptor));
        @SuppressWarnings("unchecked")
        T proxied = (T) factory.getProxy();
        return proxied;
    }

    private static IdempotencyContext contextFor(String scope, String key) {
        return IdempotencyContext.builder(scope, key).waitTimeout(Duration.ZERO).build();
    }

    interface Service {
        void handle(String messageId);

        void handleScoped(String messageId);

        String describe(String messageId);

        void fail(String messageId);
    }

    static class Recorder implements Service {

        private final List<String> calls = new ArrayList<>();

        @Override
        @Idempotent(key = "#messageId", waitTimeout = "PT0S")
        public void handle(String messageId) {
            calls.add(messageId);
        }

        @Override
        @Idempotent(key = "#messageId", scope = "orders.inbox", waitTimeout = "PT0S")
        public void handleScoped(String messageId) {
            calls.add(messageId);
        }

        @Override
        @Idempotent(key = "#messageId", waitTimeout = "PT0S", codec = "textCodec")
        public String describe(String messageId) {
            calls.add(messageId);
            return "described " + messageId;
        }

        @Override
        @Idempotent(key = "#messageId", waitTimeout = "PT0S")
        public void fail(String messageId) {
            throw new IllegalArgumentException("boom");
        }
    }

    static class MissingCodecRecorder extends Recorder {

        @Override
        @Idempotent(key = "#messageId", waitTimeout = "PT0S")
        public String describe(String messageId) {
            return "described " + messageId;
        }
    }

    static class TextCodec implements PayloadCodec<String> {

        @Override
        public Payload encode(String value) {
            return new Payload("text/plain", value.getBytes(StandardCharsets.UTF_8), java.util.Map.of());
        }

        @Override
        public String decode(Payload payload) {
            return new String(payload.body(), StandardCharsets.UTF_8);
        }
    }
}
