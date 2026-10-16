package com.ticket2h.mockpay.webhook;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.springframework.stereotype.Component;

/** Nhật ký các lần gửi webhook (giữ tối đa {@value #MAX_ENTRIES} dòng gần nhất) để script/test kiểm tra. */
@Component
public class WebhookDeliveryLog {

    static final int MAX_ENTRIES = 10_000;

    private final Deque<DeliveryAttempt> entries = new ArrayDeque<>();

    public synchronized void record(DeliveryAttempt attempt) {
        entries.addLast(attempt);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
    }

    /** Lọc theo {@code gatewayPaymentId} và/hoặc {@code eventId} (null = không lọc), theo thứ tự thời gian. */
    public synchronized List<DeliveryAttempt> find(String gatewayPaymentId, String eventId) {
        return entries.stream()
                .filter(a -> gatewayPaymentId == null || gatewayPaymentId.equals(a.gatewayPaymentId()))
                .filter(a -> eventId == null || eventId.equals(a.eventId()))
                .toList();
    }
}
