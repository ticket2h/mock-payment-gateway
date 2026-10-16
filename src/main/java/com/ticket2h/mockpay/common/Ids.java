package com.ticket2h.mockpay.common;

import java.util.UUID;

/** Sinh ID ngẫu nhiên dạng {@code <prefix>_<32 hex>}, ví dụ {@code mp_8f3a...}. */
public final class Ids {

    private Ids() {
    }

    public static String newId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
