package com.ticket2h.mockpay.payment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.ticket2h.mockpay.chaos.ChaosService;
import com.ticket2h.mockpay.common.ApiException;
import com.ticket2h.mockpay.common.Ids;
import com.ticket2h.mockpay.config.MockPayProperties;
import com.ticket2h.mockpay.webhook.WebhookDispatcher;
import com.ticket2h.mockpay.webhook.WebhookEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

/**
 * Lưu phiên thanh toán trong bộ nhớ. Mọi chuyển trạng thái của một phiên đi qua
 * {@link ConcurrentHashMap#compute}, nên nguyên tử theo từng phiên.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    static final int DEFAULT_EXPIRES_IN_SECONDS = 900;

    private final ConcurrentHashMap<String, PaymentSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> idByMerchantRef = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> idByTransactionId = new ConcurrentHashMap<>();

    private final ChaosService chaosService;
    private final WebhookDispatcher webhookDispatcher;
    private final Clock clock;
    private final String publicBaseUrl;

    public PaymentService(ChaosService chaosService, WebhookDispatcher webhookDispatcher, Clock clock,
            MockPayProperties properties) {
        this.chaosService = chaosService;
        this.webhookDispatcher = webhookDispatcher;
        this.clock = clock;
        this.publicBaseUrl = properties.publicBaseUrl();
    }

    public record CreateResult(PaymentSession session, boolean created) {
    }

    /** Idempotent theo {@code merchantRef}: gọi lại (kể cả đồng thời) luôn trả về đúng một phiên. */
    public CreateResult create(CreatePaymentRequest request) {
        boolean[] created = { false };
        String id = idByMerchantRef.computeIfAbsent(request.merchantRef(), merchantRef -> {
            Instant now = clock.instant();
            int expiresIn = request.expiresInSeconds() != null ? request.expiresInSeconds()
                    : DEFAULT_EXPIRES_IN_SECONDS;
            PaymentSession session = new PaymentSession(
                    Ids.newId("mp"), merchantRef, request.amount(), request.currency(),
                    request.description() == null ? "" : request.description(),
                    request.returnUrl(), request.notifyUrl(),
                    now, now.truncatedTo(ChronoUnit.SECONDS).plusSeconds(expiresIn),
                    chaosService.current(), PaymentStatus.PENDING, null, null, null, BigDecimal.ZERO);
            sessions.put(session.gatewayPaymentId(), session);
            created[0] = true;
            return session.gatewayPaymentId();
        });
        PaymentSession session = sessions.get(id);
        if (created[0]) {
            log.info("Payment {} created merchantRef={} amount={} {} chaos={}", id, session.merchantRef(),
                    session.amount().toPlainString(), session.currency(), session.chaos());
        }
        else if (session.amount().compareTo(request.amount()) != 0
                || !session.currency().equals(request.currency())
                || !Objects.equals(session.notifyUrl(), request.notifyUrl())) {
            log.warn("Payment {} re-requested with merchantRef={} but different parameters; returning the "
                    + "existing session unchanged", id, session.merchantRef());
        }
        return new CreateResult(session, created[0]);
    }

    public Optional<PaymentSession> findById(String gatewayPaymentId) {
        return Optional.ofNullable(sessions.get(gatewayPaymentId));
    }

    public Optional<PaymentSession> findByMerchantRef(String merchantRef) {
        return Optional.ofNullable(idByMerchantRef.get(merchantRef)).map(sessions::get);
    }

    public Optional<PaymentSession> findByTransactionId(String gatewayTransactionId) {
        return Optional.ofNullable(idByTransactionId.get(gatewayTransactionId)).map(sessions::get);
    }

    public List<PaymentSession> findAll() {
        return sessions.values().stream()
                .sorted(Comparator.comparing(PaymentSession::createdAt))
                .toList();
    }

    public PaymentSession require(String gatewayPaymentId) {
        return findById(gatewayPaymentId)
                .orElseThrow(() -> ApiException.notFound("Unknown gatewayPaymentId " + gatewayPaymentId));
    }

    public String paymentUrl(PaymentSession session) {
        return publicBaseUrl + "/pay/" + session.gatewayPaymentId();
    }

    public Instant now() {
        return clock.instant();
    }

    /**
     * Hoàn tất phiên (đúng một lần) rồi lên lịch webhook theo chaos đã chụp. Với
     * {@code webhookBeforeRedirect}, method chỉ trả về sau lần thử gửi đầu tiên.
     *
     * @throws ApiException 404 nếu không có phiên, 409 nếu phiên đã hoàn tất hoặc đã hết hạn
     */
    public PaymentSession complete(String gatewayPaymentId, PaymentResult result) {
        Instant now = clock.instant();
        PaymentSession completed = sessions.compute(gatewayPaymentId, (id, session) -> {
            if (session == null) {
                throw ApiException.notFound("Unknown gatewayPaymentId " + id);
            }
            if (session.status() != PaymentStatus.PENDING) {
                throw ApiException.conflict("ALREADY_COMPLETED",
                        "Payment " + id + " is already completed with status " + session.status());
            }
            if (session.isExpiredAt(now)) {
                throw ApiException.conflict("EXPIRED", "Payment " + id + " expired at " + session.expiresAt());
            }
            String transactionId = result == PaymentResult.SUCCESS ? Ids.newId("tx") : null;
            return session.complete(result, now, transactionId, Ids.newId("evt"));
        });
        if (completed.gatewayTransactionId() != null) {
            idByTransactionId.put(completed.gatewayTransactionId(), completed.gatewayPaymentId());
        }
        log.info("Payment {} completed result={} status={} tx={}", completed.gatewayPaymentId(), result,
                completed.status(), completed.gatewayTransactionId());

        WebhookEvent event = new WebhookEvent(
                completed.webhookEventId(), result.eventType(), completed.gatewayPaymentId(),
                completed.gatewayTransactionId(), completed.merchantRef(), completed.amount(),
                completed.currency(), DateTimeFormatter.ISO_INSTANT.format(now.truncatedTo(ChronoUnit.SECONDS)));
        webhookDispatcher.dispatch(event, completed.notifyUrl(), completed.chaos());
        return completed;
    }

    /**
     * Ghi nhận hoàn tiền {@code amount} cho phiên, nguyên tử với các lần hoàn tiền khác của cùng giao dịch.
     *
     * @throws ApiException 400 nếu tổng tiền hoàn vượt số tiền đã thanh toán
     */
    public PaymentSession applyRefund(String gatewayPaymentId, BigDecimal amount) {
        return sessions.compute(gatewayPaymentId, (id, session) -> {
            if (session == null || session.status() != PaymentStatus.SUCCEEDED) {
                throw ApiException.notFound("No successful payment " + id);
            }
            BigDecimal refundable = session.amount().subtract(session.refundedAmount());
            if (amount.compareTo(refundable) > 0) {
                throw ApiException.badRequest("AMOUNT_EXCEEDS_REFUNDABLE",
                        "Refund amount " + amount.toPlainString() + " exceeds refundable amount "
                                + refundable.toPlainString());
            }
            return session.withRefunded(amount);
        });
    }
}
