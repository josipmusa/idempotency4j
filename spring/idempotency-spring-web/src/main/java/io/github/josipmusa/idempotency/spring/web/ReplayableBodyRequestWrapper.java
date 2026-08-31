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

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Request wrapper that buffers the body once and serves a fresh stream on every
 * {@link #getInputStream()} or {@link #getReader()} call, so the filter can consume the body for
 * fingerprinting and downstream consumers (message converters, the handler) can still read it.
 *
 * <p>Spring's {@code ContentCachingRequestWrapper} is not suitable here: it caches what is read
 * but never replays it, so a body consumed before {@code chain.doFilter} would be missing
 * downstream.
 */
final class ReplayableBodyRequestWrapper extends HttpServletRequestWrapper {

    private final byte[] body;

    private ReplayableBodyRequestWrapper(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    /**
     * Buffers up to {@code maxBytesToBuffer} bytes of the request body; a negative value buffers
     * the whole body. Callers that pass a limit must reject the request when {@link #body()} turns
     * out longer than allowed - the unread remainder of a partially buffered body is not replayed.
     */
    static ReplayableBodyRequestWrapper buffer(HttpServletRequest request, long maxBytesToBuffer) throws IOException {
        byte[] body = maxBytesToBuffer < 0
                ? request.getInputStream().readAllBytes()
                : request.getInputStream().readNBytes(toIntCapped(maxBytesToBuffer));
        return new ReplayableBodyRequestWrapper(request, body);
    }

    byte[] body() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        return new BodyInputStream(body);
    }

    @Override
    public BufferedReader getReader() {
        String encoding = getCharacterEncoding();
        Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.ISO_8859_1;
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
    }

    private static int toIntCapped(long value) {
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static final class BodyInputStream extends ServletInputStream {

        private final ByteArrayInputStream delegate;

        private BodyInputStream(byte[] body) {
            this.delegate = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return delegate.read(b, off, len);
        }

        @Override
        public int available() {
            return delegate.available();
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("Non-blocking IO is not supported");
        }
    }
}
