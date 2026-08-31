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
import io.github.josipmusa.idempotency.core.IdempotencyStore;
import io.github.josipmusa.idempotency.core.StoredResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final AtomicInteger invocations = new AtomicInteger();

    private ScheduledExecutorService scheduler;
    private RecordingStore store;

    @BeforeEach
    void setUp() {
        invocations.set(0);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        store = new RecordingStore();
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
        StoredResponse stored = store.completed.get("key-1");
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

    private MockMvc mockMvc(Long maxBodyBytes) {
        AnnotationConfigWebApplicationContext wac = new AnnotationConfigWebApplicationContext();
        wac.setServletContext(new MockServletContext());
        wac.register(WebConfig.class, EchoController.class);
        wac.refresh();

        RequestMappingHandlerMapping mapping = wac.getBean(RequestMappingHandlerMapping.class);
        IdempotencyConfig config = IdempotencyConfig.defaults();
        IdempotencyEngine engine = new IdempotencyEngine(store, scheduler);
        IdempotentHandlerRegistry registry = new IdempotentHandlerRegistry(mapping, config);
        registry.afterSingletonsInstantiated();
        IdempotencyFilter filter = maxBodyBytes == null
                ? new IdempotencyFilter(engine, store, config, mapping, registry)
                : new IdempotencyFilter(engine, store, config, mapping, registry, maxBodyBytes);

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
            return "echo:" + body;
        }
    }

    private static final class RecordingStore implements IdempotencyStore {
        private final Map<String, StoredResponse> completed = new ConcurrentHashMap<>();

        @Override
        public AcquireResult tryAcquire(IdempotencyContext context) {
            StoredResponse stored = completed.get(context.key());
            return stored != null
                    ? AcquireResult.duplicate(stored)
                    : AcquireResult.acquired(UUID.randomUUID().toString());
        }

        @Override
        public void complete(String key, String leaseId, StoredResponse response, Duration ttl) {
            completed.put(key, response);
        }

        @Override
        public void release(String key, String leaseId) {}

        @Override
        public void extendLock(String key, String leaseId, Duration extension) {}

        @Override
        public int purgeExpired() {
            return 0;
        }
    }
}
