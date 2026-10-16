package com.ticket2h.mockpay.payment;

public enum PaymentStatus {

    /** Chờ người mua bấm nút trên trang thanh toán. */
    PENDING,
    SUCCEEDED,
    FAILED,
    /** Người mua bấm Huỷ (webhook gửi {@code payment.failed}). */
    CANCELLED,
    /** Hết hạn mà chưa hoàn tất (tính khi đọc, không gửi webhook). */
    EXPIRED
}
