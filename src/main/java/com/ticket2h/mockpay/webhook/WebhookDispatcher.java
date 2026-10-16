package com.ticket2h.mockpay.webhook;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.ticket2h.mockpay.chaos.ChaosConfig;
import com.ticket2h.mockpay.config.MockPayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * Gửi webhook tới {@code notifyUrl} theo cấu hình chaos đã chụp cùng phiên thanh toán.
 * <ul>
 * <li>Body được serialize <b>một lần</b> khi tạo sự kiện; mọi lượt gửi (kể cả bản trùng và lần thử lại)
 * dùng đúng mảng byte đó, nên {@code eventId} và body giữ nguyên.</li>
 * <li>Mỗi lần thử tính lại {@code X-MockPay-Timestamp} (giây epoch lúc gửi) và chữ ký trên đúng mảng byte được gửi.</li>
 * <li>Lỗi (không 2xx, quá timeout, lỗi mạng) → thử lại sau {@code base * 2^(k-1)} (mặc định 2, 4, 8, 16, 32 giây),
 * tối đa {@code max-attempts} lần.</li>
 * </ul>
 */
@Component
public class WebhookDispatcher implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    public static final String HEADER_EVENT_ID = "X-MockPay-Event-Id";
    public static final String HEADER_TIMESTAMP = "X-MockPay-Timestamp";
    public static final String HEADER_SIGNATURE = "X-MockPay-Signature";

    /** Mapper riêng cho webhook để định dạng body không phụ thuộc cấu hình {@code spring.jackson.*}. */
    private final JsonMapper webhookMapper = JsonMapper.builder()
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    private final WebhookSigner signer;
    private final WebhookDeliveryLog deliveryLog;
    private final Clock clock;
    private final MockPayProperties.Webhook settings;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService workers;

    public WebhookDispatcher(WebhookSigner signer, WebhookDeliveryLog deliveryLog, Clock clock,
            MockPayProperties properties) {
        this.signer = signer;
        this.deliveryLog = deliveryLog;
        this.clock = clock;
        this.settings = properties.webhook();
        this.workers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("mockpay-webhook-", 0).factory());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("mockpay-webhook-scheduler").daemon().factory());
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(workers)
                .build();
    }

    /**
     * Lên lịch gửi webhook cho một sự kiện. Nếu {@code chaos.webhookBeforeRedirect} bật, lần thử đầu của mọi
     * lượt gửi được thực hiện xong trước khi method trả về (các lần thử lại vẫn chạy nền).
     */
    public void dispatch(WebhookEvent event, String notifyUrl, ChaosConfig chaos) {
        OutgoingWebhook webhook = new OutgoingWebhook(event, notifyUrl, webhookMapper.writeValueAsBytes(event), chaos);

        if (chaos.dropWebhook()) {
            log.info("Webhook {} type={} payment={} dropped (chaos dropWebhook=true)",
                    event.eventId(), event.type(), event.gatewayPaymentId());
            deliveryLog.record(new DeliveryAttempt(event.eventId(), event.type(), event.gatewayPaymentId(),
                    event.merchantRef(), notifyUrl, 0, 0, DeliveryOutcome.DROPPED, null, null, null,
                    clock.instant(), null, false));
            return;
        }

        if (chaos.webhookBeforeRedirect()) {
            sleepQuietly(chaos.webhookDelayMs());
            List<Future<?>> firstAttempts = new ArrayList<>();
            for (int deliveryNo = 1; deliveryNo <= chaos.duplicateDeliveries(); deliveryNo++) {
                int no = deliveryNo;
                firstAttempts.add(workers.submit(() -> attempt(webhook, no, 1)));
            }
            awaitAll(firstAttempts);
        }
        else {
            Runnable startDeliveries = () -> {
                for (int deliveryNo = 1; deliveryNo <= chaos.duplicateDeliveries(); deliveryNo++) {
                    int no = deliveryNo;
                    workers.execute(() -> attempt(webhook, no, 1));
                }
            };
            if (chaos.webhookDelayMs() > 0) {
                scheduler.schedule(startDeliveries, chaos.webhookDelayMs(), TimeUnit.MILLISECONDS);
            }
            else {
                startDeliveries.run();
            }
        }
    }

    /** Độ trễ trước lần thử kế tiếp, sau khi lần thử thứ {@code failedAttempt} thất bại. */
    Duration retryDelay(int failedAttempt) {
        return settings.retryBaseDelay().multipliedBy(1L << (failedAttempt - 1));
    }

    private void attempt(OutgoingWebhook webhook, int deliveryNo, int attemptNo) {
        try {
            doAttempt(webhook, deliveryNo, attemptNo);
        }
        catch (RuntimeException ex) {
            log.error("Webhook {} delivery={} attempt={} crashed", webhook.event().eventId(), deliveryNo, attemptNo,
                    ex);
        }
    }

    private void doAttempt(OutgoingWebhook webhook, int deliveryNo, int attemptNo) {
        WebhookEvent event = webhook.event();
        AttemptResult result = attemptNo <= webhook.chaos().failFirstDeliveries()
                ? new AttemptResult(DeliveryOutcome.SIMULATED_NETWORK_ERROR, null,
                        "simulated network error (chaos failFirstDeliveries=" + webhook.chaos().failFirstDeliveries()
                                + ")",
                        null)
                : send(webhook);

        boolean retry = !result.outcome().isSuccess() && attemptNo < settings.maxAttempts();
        Duration nextDelay = retry ? retryDelay(attemptNo) : null;
        boolean gaveUp = !result.outcome().isSuccess() && !retry;

        deliveryLog.record(new DeliveryAttempt(event.eventId(), event.type(), event.gatewayPaymentId(),
                event.merchantRef(), webhook.notifyUrl(), deliveryNo, attemptNo, result.outcome(),
                result.httpStatus(), result.error(), result.timestamp(), clock.instant(),
                nextDelay == null ? null : nextDelay.toMillis(), gaveUp));

        log.info("Webhook {} type={} payment={} delivery={}/{} attempt={}/{} url={} outcome={}{}{}{}",
                event.eventId(), event.type(), event.gatewayPaymentId(), deliveryNo,
                webhook.chaos().duplicateDeliveries(), attemptNo, settings.maxAttempts(), webhook.notifyUrl(),
                result.outcome(),
                result.httpStatus() == null ? "" : " status=" + result.httpStatus(),
                result.error() == null ? "" : " error=\"" + result.error() + "\"",
                retry ? " retryIn=" + nextDelay.toMillis() + "ms" : (gaveUp ? " giving up" : ""));

        if (retry) {
            scheduler.schedule(() -> workers.execute(() -> attempt(webhook, deliveryNo, attemptNo + 1)),
                    nextDelay.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private AttemptResult send(OutgoingWebhook webhook) {
        long timestamp = clock.instant().getEpochSecond();
        byte[] body = webhook.body();
        String signature = signer.sign(timestamp, body);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhook.notifyUrl()))
                    .timeout(settings.timeout())
                    .header("Content-Type", "application/json")
                    .header(HEADER_EVENT_ID, webhook.event().eventId())
                    .header(HEADER_TIMESTAMP, Long.toString(timestamp))
                    .header(HEADER_SIGNATURE, signature)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            return status >= 200 && status < 300
                    ? new AttemptResult(DeliveryOutcome.DELIVERED, status, null, timestamp)
                    : new AttemptResult(DeliveryOutcome.HTTP_ERROR, status, null, timestamp);
        }
        catch (HttpConnectTimeoutException ex) {
            return new AttemptResult(DeliveryOutcome.NETWORK_ERROR, null, "connect timeout", timestamp);
        }
        catch (HttpTimeoutException ex) {
            return new AttemptResult(DeliveryOutcome.TIMEOUT, null,
                    "no response within " + settings.timeout().toMillis() + "ms", timestamp);
        }
        catch (IOException | IllegalArgumentException ex) {
            return new AttemptResult(DeliveryOutcome.NETWORK_ERROR, null, describe(ex), timestamp);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new AttemptResult(DeliveryOutcome.NETWORK_ERROR, null, "interrupted", timestamp);
        }
    }

    private void awaitAll(List<Future<?>> futures) {
        long waitMs = settings.connectTimeout().plus(settings.timeout()).toMillis() + 5_000;
        for (Future<?> future : futures) {
            try {
                future.get(waitMs, TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            catch (ExecutionException | TimeoutException ex) {
                log.warn("Synchronous webhook attempt did not finish cleanly: {}", ex.toString());
            }
        }
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static String describe(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName()
                : ex.getClass().getSimpleName() + ": " + ex.getMessage();
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
        httpClient.shutdownNow();
        workers.shutdownNow();
    }

    private record OutgoingWebhook(WebhookEvent event, String notifyUrl, byte[] body, ChaosConfig chaos) {
    }

    private record AttemptResult(DeliveryOutcome outcome, Integer httpStatus, String error, Long timestamp) {
    }
}
