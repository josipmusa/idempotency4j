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
package io.github.josipmusa.idempotency.spring.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.josipmusa.idempotency.core.AcquireResult;
import io.github.josipmusa.idempotency.core.IdempotencyConfig;
import io.github.josipmusa.idempotency.core.IdempotencyContext;
import io.github.josipmusa.idempotency.core.IdempotencyEngine;
import io.github.josipmusa.idempotency.core.IdempotencyIdentity;
import io.github.josipmusa.idempotency.core.IdempotencyLifecycleListener;
import io.github.josipmusa.idempotency.core.IdempotencyPayload;
import io.github.josipmusa.idempotency.core.IdempotencyStore;
import io.github.josipmusa.idempotency.core.StoredResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * End-to-end tests through a real {@code DispatcherServlet}: unlike the unit tests, which mock the
 * filter chain, these verify that a downstream handler can actually consume the request body after
 * the filter has read it for fingerprinting.
 */
class IdempotencyFilterIntegrationTest {

    private static final String KEY_HEADER = "Idempotency-Key";
    private static final String ECHO_SCOPE = "EchoController.echo";
    private static final String OTHER_SCOPE = "EchoController.other";
    private static final AtomicInteger invocations = new AtomicInteger();

    /** Shared timeline so listener callbacks can be ordered against the controller invocation. */
    private static final List<String> events = new CopyOnWriteArrayList<>();

    private static final AtomicReference<Thread> controllerThread = new AtomicReference<>();

    private ScheduledExecutorService scheduler;
    private RecordingStore store;
    private RecordingListener listener;

    @BeforeEach
    void setUp() {
        invocations.set(0);
        events.clear();
        controllerThread.set(null);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        store = new RecordingStore();
        listener = new RecordingListener();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void When_HandlerReadsRequestBody_Expect_BodyDeliveredAndResponseStored() throws Exception {
        MockMvc mockMvc = mockMvc(null);

        MvcResult result = mockMvc.perform(post("/echo")
                        .header(KEY_HEADER, "key-1")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello-body"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).isEqualTo("echo:hello-body");
        assertThat(invocations).hasValue(1);
        StoredResponse stored = store.completed.get(new IdempotencyIdentity(ECHO_SCOPE, "key-1"));
        assertThat(stored).isNotNull();
        assertThat(stored.statusCode()).isEqualTo(200);
        assertThat(new String(stored.body(), StandardCharsets.UTF_8)).isEqualTo("echo:hello-body");
    }

    @Test
    void When_HandlerReadsRequestBodyWithMaxBodyBytes_Expect_BodyDeliveredDownstream() throws Exception {
        MockMvc mockMvc = mockMvc(1024L);

        MvcResult result = mockMvc.perform(post("/echo")
                        .header(KEY_HEADER, "key-2")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello-body"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).isEqualTo("echo:hello-body");
        assertThat(invocations).hasValue(1);
    }

    @Test
    void When_DuplicateKey_Expect_StoredResponseReplayedWithoutReexecuting() throws Exception {
        MockMvc mockMvc = mockMvc(null);

        MvcResult first = mockMvc.perform(post("/echo")
                        .header(KEY_HEADER, "key-3")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello-body"))
                .andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(200);

        MvcResult replay = mockMvc.perform(post("/echo")
                        .header(KEY_HEADER, "key-3")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello-body"))
                .andReturn();

        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getResponse().getContentAsString()).isEqualTo("echo:hello-body");
        assertThat(invocations).hasValue(1);
    }

    @Test
    void When_HandlerRuns_Expect_AcquiredOnServletThreadBeforeControllerThenCompleted() throws Exception {
        MockMvc mockMvc = mockMvc(null);

        MvcResult result = mockMvc.perform(post("/echo")
                        .header(KEY_HEADER, "listener-1")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello-body"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(events).containsExactly("onAcquired:listener-1", "controller", "onCompleted:listener-1");
        assertThat(listener.acquiredThread).hasValue(controllerThread.get());
        assertThat(listener.completed).singleElement().isInstanceOf(StoredResponse.class);
    }

    @Test
    void When_CompletedFires_Expect_ResponseAlreadyStored() throws Exception {
        MockMvc mockMvc = mockMvc(null);

        mockMvc.perform(post("/echo")
                        .header(KEY_HEADER, "listener-2")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello-body"))
                .andReturn();

        StoredResponse observed = (StoredResponse) listener.completed.getFirst();
        assertThat(observed).isEqualTo(store.completed.get(new IdempotencyIdentity(ECHO_SCOPE, "listener-2")));
        assertThat(new String(observed.body(), StandardCharsets.UTF_8)).isEqualTo("echo:hello-body");
    }

    @Test
    void When_DuplicateKey_Expect_ListenerSeesDuplicateWithStoredPayload() throws Exception {
        MockMvc mockMvc = mockMvc(null);

        mockMvc.perform(post("/echo")
                .header(KEY_HEADER, "listener-3")
                .contentType(MediaType.TEXT_PLAIN)
                .content("hello-body"));
        mockMvc.perform(post("/echo")
                .header(KEY_HEADER, "listener-3")
                .contentType(MediaType.TEXT_PLAIN)
                .content("hello-body"));

        assertThat(events)
                .containsExactly(
                        "onAcquired:listener-3", "controller", "onCompleted:listener-3", "onDuplicate:listener-3");
        assertThat(listener.duplicates).singleElement().isInstanceOf(StoredResponse.class);
    }

    @Test
    void When_SameKeyOnTwoHandlers_Expect_IndependentRecords() throws Exception {
        MockMvc mockMvc = mockMvc(null);

        MvcResult echo = mockMvc.perform(post("/echo")
                        .header(KEY_HEADER, "shared-key")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("same-body"))
                .andReturn();
        MvcResult other = mockMvc.perform(post("/other")
                        .header(KEY_HEADER, "shared-key")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("same-body"))
                .andReturn();

        assertThat(echo.getResponse().getContentAsString()).isEqualTo("echo:same-body");
        assertThat(other.getResponse().getContentAsString()).isEqualTo("other:same-body");
        assertThat(other.getResponse().getHeader("Idempotent-Replayed"))
                .as("The second handler must run, not replay the first handler's response")
                .isNull();
        assertThat(invocations).hasValue(2);
        assertThat(store.completed)
                .containsOnlyKeys(
                        new IdempotencyIdentity(ECHO_SCOPE, "shared-key"),
                        new IdempotencyIdentity(OTHER_SCOPE, "shared-key"));
    }

    @Test
    void When_ListenerThrows_Expect_ResponseAndStorageUnaffected() throws Exception {
        MockMvc mockMvc = mockMvc(null, List.of(new ThrowingListener()));

        MvcResult result = mockMvc.perform(post("/echo")
                        .header(KEY_HEADER, "listener-4")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello-body"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).isEqualTo("echo:hello-body");
        assertThat(invocations).hasValue(1);
        assertThat(store.completed).containsKey(new IdempotencyIdentity(ECHO_SCOPE, "listener-4"));
    }

    private MockMvc mockMvc(Long maxBodyBytes) {
        return mockMvc(maxBodyBytes, List.of(listener));
    }

    private MockMvc mockMvc(Long maxBodyBytes, List<IdempotencyLifecycleListener> listeners) {
        AnnotationConfigWebApplicationContext wac = new AnnotationConfigWebApplicationContext();
        wac.setServletContext(new MockServletContext());
        wac.register(WebConfig.class, EchoController.class);
        wac.refresh();

        RequestMappingHandlerMapping mapping = wac.getBean(RequestMappingHandlerMapping.class);
        IdempotencyConfig config = IdempotencyConfig.defaults();
        WebIdempotencyConfig webConfig = WebIdempotencyConfig.defaults();
        IdempotencyEngine engine = new IdempotencyEngine(store, scheduler, listeners);
        IdempotentHandlerRegistry registry = new IdempotentHandlerRegistry(mapping, config);
        registry.afterSingletonsInstantiated();
        IdempotencyFilter filter = maxBodyBytes == null
                ? new IdempotencyFilter(engine, webConfig, mapping, registry)
                : new IdempotencyFilter(engine, webConfig, mapping, registry, maxBodyBytes);

        return MockMvcBuilders.webAppContextSetup(wac).addFilters(filter).build();
    }

    @EnableWebMvc
    static class WebConfig {}

    @RestController
    static class EchoController {
        @PostMapping(value = "/echo", consumes = MediaType.TEXT_PLAIN_VALUE)
        @Idempotent
        public String echo(@RequestBody String body) {
            invocations.incrementAndGet();
            events.add("controller");
            controllerThread.set(Thread.currentThread());
            return "echo:" + body;
        }

        @PostMapping(value = "/other", consumes = MediaType.TEXT_PLAIN_VALUE)
        @Idempotent
        public String other(@RequestBody String body) {
            invocations.incrementAndGet();
            events.add("other-controller");
            return "other:" + body;
        }
    }

    private static final class RecordingListener implements IdempotencyLifecycleListener {

        private final AtomicReference<Thread> acquiredThread = new AtomicReference<>();
        private final List<IdempotencyPayload> completed = new CopyOnWriteArrayList<>();
        private final List<IdempotencyPayload> duplicates = new CopyOnWriteArrayList<>();

        @Override
        public void onAcquired(IdempotencyContext ctx, String leaseId) {
            events.add("onAcquired:" + ctx.key());
            acquiredThread.set(Thread.currentThread());
        }

        @Override
        public void onCompleted(IdempotencyContext ctx, String leaseId, IdempotencyPayload payload) {
            events.add("onCompleted:" + ctx.key());
            completed.add(payload);
        }

        @Override
        public void onFailed(IdempotencyContext ctx, String leaseId, Throwable cause, FailurePhase phase) {
            events.add("onFailed:" + phase);
        }

        @Override
        public void onDuplicate(IdempotencyContext ctx, IdempotencyPayload payload) {
            events.add("onDuplicate:" + ctx.key());
            duplicates.add(payload);
        }
    }

    private static final class ThrowingListener implements IdempotencyLifecycleListener {

        @Override
        public void onAcquired(IdempotencyContext ctx, String leaseId) {
            throw new IllegalStateException("listener failed on acquire");
        }

        @Override
        public void onCompleted(IdempotencyContext ctx, String leaseId, IdempotencyPayload payload) {
            throw new IllegalStateException("listener failed on completion");
        }
    }

    private static final class RecordingStore implements IdempotencyStore {
        private final Map<IdempotencyIdentity, StoredResponse> completed = new ConcurrentHashMap<>();

        @Override
        public AcquireResult tryAcquire(IdempotencyContext context) {
            StoredResponse stored = completed.get(context.identity());
            return stored != null
                    ? AcquireResult.duplicate(stored)
                    : AcquireResult.acquired(UUID.randomUUID().toString());
        }

        @Override
        public void complete(IdempotencyIdentity identity, String leaseId, IdempotencyPayload payload, Duration ttl) {
            completed.put(identity, (StoredResponse) payload);
        }

        @Override
        public void release(IdempotencyIdentity identity, String leaseId) {
            // Not needed
        }

        @Override
        public void extendLock(IdempotencyIdentity identity, String leaseId, Duration extension) {
            // Not needed
        }

        @Override
        public int purgeExpired() {
            return 0;
        }
    }
}
