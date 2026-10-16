package com.ticket2h.mockpay.payment;

import com.ticket2h.mockpay.common.ApiException;
import com.ticket2h.mockpay.payment.PaymentDtos.CreatePaymentResponse;
import com.ticket2h.mockpay.payment.PaymentDtos.PaymentStatusResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API cho merchant (cần {@code X-Api-Key}). */
@RestController
@RequestMapping("/api/v1/payments")
class PaymentApiController {

    private final PaymentService paymentService;

    PaymentApiController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** Lần đầu {@code 201}; gọi lại cùng {@code merchantRef} → {@code 200} với cùng body. */
    @PostMapping
    ResponseEntity<CreatePaymentResponse> create(@Valid @RequestBody CreatePaymentRequest request) {
        PaymentService.CreateResult result = paymentService.create(request);
        PaymentSession session = result.session();
        CreatePaymentResponse body = new CreatePaymentResponse(session.gatewayPaymentId(),
                paymentService.paymentUrl(session), session.expiresAt());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(body);
    }

    @GetMapping("/by-ref/{merchantRef}")
    PaymentStatusResponse byMerchantRef(@PathVariable String merchantRef) {
        PaymentSession session = paymentService.findByMerchantRef(merchantRef)
                .orElseThrow(() -> ApiException.notFound("Unknown merchantRef " + merchantRef));
        return new PaymentStatusResponse(session.effectiveStatus(paymentService.now()), session.gatewayPaymentId(),
                session.gatewayTransactionId(), session.amount(), session.currency());
    }
}
