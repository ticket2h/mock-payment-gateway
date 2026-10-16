package com.ticket2h.mockpay.payment;

import java.util.List;

import com.ticket2h.mockpay.payment.PaymentDtos.AdminPaymentView;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Công cụ cho test và script (không cần key, chỉ dùng local). */
@RestController
@RequestMapping("/admin/payments")
class AdminPaymentController {

    private final PaymentService paymentService;

    AdminPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping
    List<AdminPaymentView> list(@RequestParam(required = false) String merchantRef) {
        return paymentService.findAll().stream()
                .filter(s -> merchantRef == null || merchantRef.equals(s.merchantRef()))
                .map(this::view)
                .toList();
    }

    @GetMapping("/{gatewayPaymentId}")
    AdminPaymentView get(@PathVariable String gatewayPaymentId) {
        return view(paymentService.require(gatewayPaymentId));
    }

    /** Hoàn tất không cần trình duyệt: như bấm nút trên trang thanh toán nhưng không redirect. */
    @PostMapping("/{gatewayPaymentId}/complete")
    AdminPaymentView complete(@PathVariable String gatewayPaymentId, @RequestParam PaymentResult result) {
        return view(paymentService.complete(gatewayPaymentId, result));
    }

    private AdminPaymentView view(PaymentSession s) {
        return new AdminPaymentView(s.gatewayPaymentId(), s.merchantRef(), s.effectiveStatus(paymentService.now()),
                s.amount(), s.currency(), s.description(), paymentService.paymentUrl(s), s.returnUrl(),
                s.notifyUrl(), s.createdAt(), s.expiresAt(), s.completedAt(), s.gatewayTransactionId(),
                s.webhookEventId(), s.refundedAmount(), s.chaos());
    }
}
