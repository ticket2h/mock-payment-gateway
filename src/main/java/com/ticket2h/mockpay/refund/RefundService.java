package com.ticket2h.mockpay.refund;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

import com.ticket2h.mockpay.chaos.ChaosRandom;
import com.ticket2h.mockpay.chaos.ChaosService;
import com.ticket2h.mockpay.common.ApiException;
import com.ticket2h.mockpay.common.Ids;
import com.ticket2h.mockpay.payment.PaymentService;
import com.ticket2h.mockpay.payment.PaymentSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

/**
 * Hoàn tiền idempotent theo {@code idempotencyKey}.
 * <p>
 * Kết quả chỉ được lưu khi hoàn tiền thành công ({@link ConcurrentHashMap#computeIfAbsent} không ghi gì nếu
 * hàm tính ném lỗi), nên lần gọi bị 404/400/503 không "tiêu" key và có thể gọi lại. Các lần gọi đồng thời cùng
 * key được tuần tự hoá trên key đó, nên không bao giờ hoàn hai lần.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final ConcurrentHashMap<String, Refund> refundsByKey = new ConcurrentHashMap<>();

    private final PaymentService paymentService;
    private final ChaosService chaosService;
    private final ChaosRandom random;
    private final Clock clock;

    public RefundService(PaymentService paymentService, ChaosService chaosService, ChaosRandom random, Clock clock) {
        this.paymentService = paymentService;
        this.chaosService = chaosService;
        this.random = random;
        this.clock = clock;
    }

    /**
     * @throws ApiException 404 nếu không có giao dịch, 400 nếu số tiền vượt số đã thanh toán (hoặc còn lại
     *                      có thể hoàn), 503 khi chaos {@code refundFailureRate} kích hoạt
     */
    public Refund refund(RefundRequest request) {
        Refund existing = refundsByKey.get(request.idempotencyKey());
        if (existing != null) {
            logReplay(existing, request);
            return existing;
        }
        return refundsByKey.computeIfAbsent(request.idempotencyKey(), key -> execute(request));
    }

    private Refund execute(RefundRequest request) {
        PaymentSession payment = paymentService.findByTransactionId(request.gatewayTransactionId())
                .orElseThrow(() -> ApiException.notFound(
                        "Unknown gatewayTransactionId " + request.gatewayTransactionId()));
        if (request.amount().compareTo(payment.amount()) > 0) {
            throw ApiException.badRequest("AMOUNT_EXCEEDS_PAYMENT", "Refund amount "
                    + request.amount().toPlainString() + " exceeds paid amount " + payment.amount().toPlainString());
        }

        double failureRate = chaosService.current().refundFailureRate();
        if (failureRate > 0 && random.nextDouble() < failureRate) {
            log.info("Refund idempotencyKey={} tx={} failed with 503 (chaos refundFailureRate={})",
                    request.idempotencyKey(), request.gatewayTransactionId(), failureRate);
            throw ApiException.unavailable("REFUND_TEMPORARILY_UNAVAILABLE",
                    "Refund service temporarily unavailable, retry with the same idempotencyKey");
        }

        paymentService.applyRefund(payment.gatewayPaymentId(), request.amount());
        Refund refund = new Refund(Ids.newId("rf"), request.idempotencyKey(), request.gatewayTransactionId(),
                payment.gatewayPaymentId(), request.amount(), Refund.SUCCEEDED, clock.instant());
        log.info("Refund {} succeeded idempotencyKey={} tx={} amount={}", refund.refundId(),
                refund.idempotencyKey(), refund.gatewayTransactionId(), refund.amount().toPlainString());
        return refund;
    }

    private static void logReplay(Refund existing, RefundRequest request) {
        if (!existing.gatewayTransactionId().equals(request.gatewayTransactionId())
                || existing.amount().compareTo(request.amount()) != 0) {
            log.warn("Refund idempotencyKey={} replayed with different parameters; returning original refund {}",
                    request.idempotencyKey(), existing.refundId());
        }
    }
}
