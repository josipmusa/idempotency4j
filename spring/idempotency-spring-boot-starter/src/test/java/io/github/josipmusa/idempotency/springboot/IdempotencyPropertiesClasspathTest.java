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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The properties are bound in every application that uses the starter, whatever its store, so
 * they must not need a JDBC-only module such as {@code spring-boot-sql} on the classpath.
 */
class IdempotencyPropertiesClasspathTest {

    @Test
    void When_SpringBootSqlAbsent_Expect_PropertiesBindable() throws Exception {
        try (URLClassLoader loader = classpathWithout("spring-boot-sql")) {
            assertThat(loader.getResource("org/springframework/boot/sql/init/DatabaseInitializationMode.class"))
                    .isNull();

            Class<?> properties = Class.forName(IdempotencyProperties.class.getName(), true, loader);

            assertThatCode(() -> {
                        properties.getDeclaredConstructor().newInstance();
                        // What the binder does: read every getter and setter of every nested type.
                        for (Class<?> type : properties.getDeclaredClasses()) {
                            type.getDeclaredMethods();
                        }
                    })
                    .doesNotThrowAnyException();
        }
    }

    private static URLClassLoader classpathWithout(String artifactId) {
        URL[] urls = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> !new File(entry).getName().startsWith(artifactId + "-"))
                .map(IdempotencyPropertiesClasspathTest::url)
                .toArray(URL[]::new);
        return new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
    }

    private static URL url(String entry) {
        try {
            return new File(entry).toURI().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }
}
