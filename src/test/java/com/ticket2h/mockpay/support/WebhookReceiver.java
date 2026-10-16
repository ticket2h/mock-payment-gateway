package com.ticket2h.mockpay.support;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Merchant giả nhận webhook trên cổng ngẫu nhiên: ghi lại mọi request (header, đúng mảng byte body),
 * tự kiểm tra chữ ký HMAC và trả mã HTTP theo {@link Behavior} cấu hình được.
 */
public final class WebhookReceiver implements AutoCloseable {

    public static final String PATH = "/webhooks/payments/mockpay";

    /** Quyết định mã HTTP trả về cho request thứ {@code requestNo} (bắt đầu từ 1); có thể sleep để mô phỏng chậm. */
    @FunctionalInterface
    public interface Behavior {
        int respond(int requestNo) throws Exception;
    }

    public record Received(int requestNo, Map<String, String> headers, byte[] body, Instant receivedAt,
            boolean signatureValid) {

        public String header(String name) {
            return headers.get(name);
        }

        public String bodyString() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final String secret;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger requestCounter = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private volatile Behavior behavior = requestNo -> 200;

    private WebhookReceiver(String secret) throws IOException {
        this.secret = secret;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.setExecutor(executor);
        this.server.createContext(PATH, this::handle);
        this.server.start();
    }

    public static WebhookReceiver start(String secret) {
        try {
            return new WebhookReceiver(secret);
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + PATH;
    }

    public void respondWith(Behavior behavior) {
        this.behavior = behavior;
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    public int maxInFlight() {
        return maxInFlight.get();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        int current = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(current, Math::max);
        try (exchange) {
            byte[] body;
            try (InputStream in = exchange.getRequestBody()) {
                body = in.readAllBytes();
            }
            Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, String.join(",", values)));
            int requestNo = requestCounter.incrementAndGet();
            received.add(new Received(requestNo, headers, body, Instant.now(),
                    verify(headers.get("X-MockPay-Timestamp"), body, headers.get("X-MockPay-Signature"))));

            int status;
            try {
                status = behavior.respond(requestNo);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            catch (Exception ex) {
                status = 500;
            }
            try {
                exchange.sendResponseHeaders(status, -1);
            }
            catch (IOException ignored) {
                // MockPay đã bỏ cuộc (timeout) trước khi ta trả lời
            }
        }
        finally {
            inFlight.decrementAndGet();
        }
    }

    /** Tính lại chữ ký độc lập: hex(HMAC_SHA256(secret, timestamp + "." + rawBody)). */
    private boolean verify(String timestamp, byte[] body, String signature) {
        if (timestamp == null || signature == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] prefix = (timestamp + ".").getBytes(StandardCharsets.UTF_8);
            byte[] signed = new byte[prefix.length + body.length];
            System.arraycopy(prefix, 0, signed, 0, prefix.length);
            System.arraycopy(body, 0, signed, prefix.length, body.length);
            return HexFormat.of().formatHex(mac.doFinal(signed)).equals(signature);
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
