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
import io.github.josipmusa.idempotency.spring.web.IdempotencyFilter;
import io.github.josipmusa.idempotency.spring.web.IdempotentHandlerRegistry;
import io.github.josipmusa.idempotency.spring.web.ResponseSanitizer;
import io.github.josipmusa.idempotency.spring.web.WebIdempotencyConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The HTTP half of the starter: the filter and the settings only it reads.
 *
 * <p>Active only in a Servlet web application with Spring MVC on the classpath, so a
 * message-driven application that depends on this starter gets an engine and a method
 * interceptor without a filter it has no use for.
 */
@AutoConfiguration(after = IdempotencyAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({IdempotencyFilter.class, RequestMappingHandlerMapping.class})
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    WebIdempotencyConfig webIdempotencyConfig(IdempotencyProperties properties) {
        IdempotencyProperties.Web web = properties.getWeb();
        return WebIdempotencyConfig.builder()
                .keyHeader(web.getKeyHeader())
                .required(web.isRequired())
                .inFlightStatus(web.getInFlightStatus())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    ResponseSanitizer responseSanitizer() {
        return response -> response;
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotentHandlerRegistry idempotentHandlerRegistry(
            RequestMappingHandlerMapping handlerMapping, IdempotencyConfig config) {
        return new IdempotentHandlerRegistry(handlerMapping, config);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(IdempotencyEngine.class)
    public IdempotencyFilter idempotencyFilter(
            IdempotencyEngine engine,
            WebIdempotencyConfig webConfig,
            RequestMappingHandlerMapping handlerMapping,
            IdempotentHandlerRegistry registry,
            IdempotencyProperties properties,
            ResponseSanitizer sanitizer) {
        return new IdempotencyFilter(
                engine, webConfig, handlerMapping, registry, properties.getWeb().getMaxBodyBytes(), sanitizer);
    }

    @Bean
    @ConditionalOnBean(IdempotencyFilter.class)
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(
            IdempotencyFilter idempotencyFilter, IdempotencyProperties properties) {
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(idempotencyFilter);
        registration.setOrder(properties.getWeb().getFilterOrder());
        registration.addUrlPatterns("/*");
        return registration;
    }
}
