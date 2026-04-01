package io.github.josipmusa.idempotency.springboot;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "idempotency")
public class IdempotencyProperties {

    private String keyHeader = "Idempotency-Key";
    private Duration defaultTtl = Duration.ofHours(24);
    private Duration defaultLockTimeout = Duration.ofSeconds(10);
    private int filterOrder = 0;
    private Purge purge = new Purge();

    public String getKeyHeader() {
        return keyHeader;
    }

    public void setKeyHeader(String keyHeader) {
        this.keyHeader = keyHeader;
    }

    public Duration getDefaultTtl() {
        return defaultTtl;
    }

    public void setDefaultTtl(Duration defaultTtl) {
        this.defaultTtl = defaultTtl;
    }

    public Duration getDefaultLockTimeout() {
        return defaultLockTimeout;
    }

    public void setDefaultLockTimeout(Duration defaultLockTimeout) {
        this.defaultLockTimeout = defaultLockTimeout;
    }

    public int getFilterOrder() {
        return filterOrder;
    }

    public void setFilterOrder(int filterOrder) {
        this.filterOrder = filterOrder;
    }

    public Purge getPurge() {
        return purge;
    }

    public void setPurge(Purge purge) {
        this.purge = purge;
    }

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
