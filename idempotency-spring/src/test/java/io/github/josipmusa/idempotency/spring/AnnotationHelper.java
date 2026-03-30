package io.github.josipmusa.idempotency.spring;

import java.lang.annotation.Annotation;

class AnnotationHelper {

    private AnnotationHelper() {}

    static Idempotent annotation(boolean required) {
        return annotation(required, "", "");
    }

    static Idempotent annotation(boolean required, String ttl, String lockTimeout) {
        return new Idempotent() {
            @Override
            public String ttl() {
                return ttl;
            }

            @Override
            public String lockTimeout() {
                return lockTimeout;
            }

            @Override
            public boolean required() {
                return required;
            }

            @Override
            public Class<? extends Annotation> annotationType() {
                return Idempotent.class;
            }
        };
    }
}
