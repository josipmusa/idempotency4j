package io.github.josipmusa.idempotency.springboot;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.Ordered;

@ConfigurationProperties(prefix = "idempotency")
public class IdempotencyProperties {

    private String keyHeader = "Idempotency-Key";
    private boolean keyRequired = true;
    private Duration defaultTtl = Duration.ofHours(24);
    private Duration defaultLockTimeout = Duration.ofSeconds(10);
    private int filterOrder = Ordered.HIGHEST_PRECEDENCE + 1;

    public String getKeyHeader() {
        return keyHeader;
    }

    public void setKeyHeader(String keyHeader) {
        this.keyHeader = keyHeader;
    }

    public boolean isKeyRequired() {
        return keyRequired;
    }

    public void setKeyRequired(boolean keyRequired) {
        this.keyRequired = keyRequired;
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
}
