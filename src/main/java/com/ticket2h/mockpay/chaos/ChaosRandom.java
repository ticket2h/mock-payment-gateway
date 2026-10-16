package com.ticket2h.mockpay.chaos;

/** Nguồn ngẫu nhiên cho chaos, tách ra để test có thể thay bằng chuỗi giá trị cố định. */
@FunctionalInterface
public interface ChaosRandom {

    /** Số ngẫu nhiên trong {@code [0, 1)}. */
    double nextDouble();
}
