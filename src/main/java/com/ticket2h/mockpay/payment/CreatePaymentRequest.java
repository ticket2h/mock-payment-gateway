package com.ticket2h.mockpay.payment;

import java.math.BigDecimal;

import com.ticket2h.mockpay.common.HttpUrl;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code POST /api/v1/payments}.
 *
 * @param amount           giữ nguyên giá trị (kể cả scale, ví dụ {@code 4600000.00}) để gửi lại trong webhook
 * @param expiresInSeconds mặc định 900 nếu bỏ trống
 */
public record CreatePaymentRequest(
        @NotBlank @Size(max = 128) String merchantRef,
        @NotNull @Positive BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO 4217 code") String currency,
        @Size(max = 500) String description,
        @NotBlank @HttpUrl @Size(max = 2048) String returnUrl,
        @NotBlank @HttpUrl @Size(max = 2048) String notifyUrl,
        @Min(1) @Max(604_800) Integer expiresInSeconds) {
}
