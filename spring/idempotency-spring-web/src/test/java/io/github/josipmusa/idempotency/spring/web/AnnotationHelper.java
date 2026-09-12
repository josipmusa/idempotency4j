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

import io.github.josipmusa.idempotency.core.CompletionMode;
import io.github.josipmusa.idempotency.spring.Idempotent;
import java.lang.annotation.Annotation;

class AnnotationHelper {

    private AnnotationHelper() {}

    static Idempotent annotation() {
        return annotation("", "", "");
    }

    static Idempotent annotation(String ttl, String lease) {
        return annotation(ttl, lease, "");
    }

    static Idempotent annotation(String ttl, String lease, String waitTimeout) {
        return annotation(ttl, lease, waitTimeout, "");
    }

    static Idempotent annotation(String ttl, String lease, String waitTimeout, String scope) {
        return new Idempotent() {
            @Override
            public String key() {
                return "";
            }

            @Override
            public String scope() {
                return scope;
            }

            @Override
            public String ttl() {
                return ttl;
            }

            @Override
            public String lease() {
                return lease;
            }

            @Override
            public String waitTimeout() {
                return waitTimeout;
            }

            @Override
            public CompletionMode completion() {
                return CompletionMode.AUTONOMOUS;
            }

            @Override
            public String codec() {
                return "";
            }

            @Override
            public Class<? extends Annotation> annotationType() {
                return Idempotent.class;
            }
        };
    }
}
