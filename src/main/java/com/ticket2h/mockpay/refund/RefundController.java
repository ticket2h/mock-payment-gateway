package com.ticket2h.mockpay.refund;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/refunds")
class RefundController {

    private final RefundService refundService;

    RefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    /** {@code 200} {@code {refundId, status}}; cùng {@code idempotencyKey} → cùng kết quả. */
    @PostMapping
    RefundResponse refund(@Valid @RequestBody RefundRequest request) {
        Refund refund = refundService.refund(request);
        return new RefundResponse(refund.refundId(), refund.status());
    }

    record RefundResponse(String refundId, String status) {
    }
}
