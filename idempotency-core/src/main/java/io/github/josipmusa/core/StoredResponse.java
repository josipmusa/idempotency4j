package io.github.josipmusa.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record StoredResponse(int statusCode, Map<String, List<String>> headers, byte[] body, Instant completedAt) {

    // Defensive copy
    public StoredResponse {
        headers = Map.copyOf(headers);
        body = body.clone();
    }
}
