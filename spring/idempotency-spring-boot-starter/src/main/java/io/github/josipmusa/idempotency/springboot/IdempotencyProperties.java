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

import io.github.josipmusa.idempotency.core.CompletionFailurePolicy;
import io.github.josipmusa.idempotency.core.CompletionMode;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.sql.init.DatabaseInitializationMode;

/**
 * Everything the starter can be configured with, under the {@code idempotency} prefix.
 *
 * <p>Transport-neutral settings sit at the top level, because they are answers about the
 * library as a whole. Settings that only make sense over HTTP live under {@code web}, and
 * settings that only make sense for a JDBC store live under {@code jdbc}, so an application
 * that uses neither never has to read past the first group.
 */
@ConfigurationProperties(prefix = "idempotency")
public class IdempotencyProperties {

    private Duration defaultTtl = Duration.ofHours(24);
    private Duration defaultLease = Duration.ofSeconds(30);
    private Duration defaultWait = Duration.ofSeconds(10);

    /**
     * Whether an operation that does not choose for itself records its completion on its own
     * or inside the transaction it is already running in.
     */
    private CompletionMode completionMode = CompletionMode.AUTONOMOUS;

    /**
     * The starter's default rather than the engine's: a response the action already produced
     * should still reach the caller when the store could not record it. The idempotency
     * guarantee is lost for that key - a later duplicate re-executes - but the request itself
     * succeeds. Set {@code propagate} where losing the guarantee silently is worse than
     * failing the request.
     */
    private CompletionFailurePolicy completionFailurePolicy = CompletionFailurePolicy.LOG_AND_RETURN;

    /** Which store the starter builds, if any. */
    private StoreType storeType = StoreType.AUTO;

    private Jdbc jdbc = new Jdbc();
    private Web web = new Web();
    private Purge purge = new Purge();

    public Duration getDefaultTtl() {
        return defaultTtl;
    }

    public void setDefaultTtl(Duration defaultTtl) {
        this.defaultTtl = defaultTtl;
    }

    public Duration getDefaultLease() {
        return defaultLease;
    }

    public void setDefaultLease(Duration defaultLease) {
        this.defaultLease = defaultLease;
    }

    public Duration getDefaultWait() {
        return defaultWait;
    }

    public void setDefaultWait(Duration defaultWait) {
        this.defaultWait = defaultWait;
    }

    public CompletionMode getCompletionMode() {
        return completionMode;
    }

    public void setCompletionMode(CompletionMode completionMode) {
        this.completionMode = completionMode;
    }

    public CompletionFailurePolicy getCompletionFailurePolicy() {
        return completionFailurePolicy;
    }

    public void setCompletionFailurePolicy(CompletionFailurePolicy completionFailurePolicy) {
        this.completionFailurePolicy = completionFailurePolicy;
    }

    public StoreType getStoreType() {
        return storeType;
    }

    public void setStoreType(StoreType storeType) {
        this.storeType = storeType;
    }

    public Jdbc getJdbc() {
        return jdbc;
    }

    public void setJdbc(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public Web getWeb() {
        return web;
    }

    public void setWeb(Web web) {
        this.web = web;
    }

    public Purge getPurge() {
        return purge;
    }

    public void setPurge(Purge purge) {
        this.purge = purge;
    }

    /**
     * Which backend the starter builds an {@link io.github.josipmusa.idempotency.core.IdempotencyStore}
     * from.
     *
     * <p>An application that declares its own store bean silences all of this: the starter
     * never replaces a store the application built itself.
     */
    public enum StoreType {

        /**
         * Pick from what is on the classpath: a JDBC store when {@code idempotency-jdbc} and a
         * single {@code DataSource} are both present, and nothing otherwise.
         *
         * <p>Deliberately does not fall back to the in-memory store. An in-memory inbox
         * deduplicates within one JVM until it restarts, which is not a property anything
         * should acquire by accident - ask for it by name.
         */
        AUTO,

        /** Always build a JDBC store; fail at startup if the provider or a DataSource is missing. */
        JDBC,

        /**
         * Always build an in-memory store. Suitable for tests and single-node development, never
         * for an application that runs more than one instance or expects to survive a restart.
         */
        IN_MEMORY,

        /** Build nothing. The application supplies its own store bean, or has none. */
        NONE
    }

    /** Settings that only apply to a JDBC store the starter built. */
    public static class Jdbc {

        /**
         * Whether the starter creates the {@code idempotency_records} table on startup.
         *
         * <p>{@code embedded} follows the convention Spring Boot uses for Session and Quartz:
         * a development database gets its schema for free, while a real one does not get DDL
         * from a library behind its owner's back. Point your schema tool at
         * {@code idempotency-schema-postgresql.sql} or {@code idempotency-schema-mysql.sql},
         * shipped in the provider jar, when this is {@code never}.
         */
        private DatabaseInitializationMode initializeSchema = DatabaseInitializationMode.EMBEDDED;

        public DatabaseInitializationMode getInitializeSchema() {
            return initializeSchema;
        }

        public void setInitializeSchema(DatabaseInitializationMode initializeSchema) {
            this.initializeSchema = initializeSchema;
        }
    }

    /** Settings that only make sense for the HTTP filter. */
    public static class Web {

        private String keyHeader = "Idempotency-Key";

        /** Whether a request to an idempotent endpoint without a key is rejected with 422. */
        private boolean required = true;

        /** The status a request rejected because another caller holds the key gets. */
        private int inFlightStatus = 409;

        private long maxBodyBytes = 1_048_576L; // 1 MiB
        private int filterOrder = 0;

        public String getKeyHeader() {
            return keyHeader;
        }

        public void setKeyHeader(String keyHeader) {
            this.keyHeader = keyHeader;
        }

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }

        public int getInFlightStatus() {
            return inFlightStatus;
        }

        public void setInFlightStatus(int inFlightStatus) {
            this.inFlightStatus = inFlightStatus;
        }

        public long getMaxBodyBytes() {
            return maxBodyBytes;
        }

        public void setMaxBodyBytes(long maxBodyBytes) {
            this.maxBodyBytes = maxBodyBytes;
        }

        public int getFilterOrder() {
            return filterOrder;
        }

        public void setFilterOrder(int filterOrder) {
            this.filterOrder = filterOrder;
        }
    }

    /** Settings for the scheduled deletion of expired records. */
    public static class Purge {

        private boolean enabled = true;
        private String cron = "0 0 * * * *";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }
    }
}
