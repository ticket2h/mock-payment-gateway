package com.ticket2h.mockpay.payment;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.ticket2h.mockpay.common.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/** Trang thanh toán cho người mua (HTML, không cần key). */
@Controller
@RequestMapping("/pay")
class PayPageController {

    private static final MediaType HTML = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy")
            .withZone(ZoneId.systemDefault());

    private final PaymentService paymentService;

    PayPageController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/{gatewayPaymentId}")
    ResponseEntity<String> page(@PathVariable String gatewayPaymentId) {
        PaymentSession session = paymentService.require(gatewayPaymentId);
        PaymentStatus status = session.effectiveStatus(paymentService.now());
        String body = switch (status) {
            case PENDING -> """
                    <form method="post" action="/pay/%s/complete">
                      <button class="ok" type="submit" name="result" value="SUCCESS">Thành công</button>
                      <button class="fail" type="submit" name="result" value="FAILED">Thất bại</button>
                      <button class="cancel" type="submit" name="result" value="CANCELLED">Huỷ</button>
                    </form>
                    <p class="hint">Hết hạn lúc %s</p>
                    """.formatted(esc(session.gatewayPaymentId()), esc(TIME_FORMAT.format(session.expiresAt())));
            case EXPIRED -> "<p class=\"state\">Phiên thanh toán đã hết hạn lúc "
                    + esc(TIME_FORMAT.format(session.expiresAt())) + ".</p>";
            default -> "<p class=\"state\">Phiên thanh toán đã hoàn tất: <strong>" + status + "</strong></p>";
        };
        String content = """
                <p class="label">Số tiền</p>
                <p class="amount">%s <span>%s</span></p>
                <p class="desc">%s</p>
                %s
                <dl>
                  <dt>Mã phiên</dt><dd>%s</dd>
                  <dt>Mã đơn của merchant</dt><dd>%s</dd>
                </dl>
                """.formatted(esc(formatAmount(session.amount())), esc(session.currency()),
                esc(session.description()), body, esc(session.gatewayPaymentId()), esc(session.merchantRef()));
        return ResponseEntity.ok().contentType(HTML).body(layout("Thanh toán " + session.gatewayPaymentId(), content));
    }

    /**
     * Người mua bấm nút: hoàn tất phiên, lên lịch webhook, rồi chuyển trình duyệt về
     * {@code returnUrl?merchantRef=...&status=...}. Tham số này cố ý không ký: merchant không được tin nó.
     */
    @PostMapping("/{gatewayPaymentId}/complete")
    ResponseEntity<String> complete(@PathVariable String gatewayPaymentId,
            @RequestParam(required = false) String result) {
        PaymentResult paymentResult = parseResult(result);
        PaymentSession session = paymentService.complete(gatewayPaymentId, paymentResult);
        URI location = UriComponentsBuilder.fromUriString(session.returnUrl())
                .queryParam("merchantRef", UriUtils.encode(session.merchantRef(), StandardCharsets.UTF_8))
                .queryParam("status", paymentResult.name())
                .build(true)
                .toUri();
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(location).build();
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<String> handleApiException(ApiException ex) {
        String content = "<p class=\"label\">Lỗi " + ex.status().value() + "</p><p class=\"state\">"
                + esc(ex.getMessage()) + "</p>";
        return ResponseEntity.status(ex.status()).contentType(HTML).body(layout("MockPay: lỗi", content));
    }

    private static PaymentResult parseResult(String result) {
        if (result != null) {
            for (PaymentResult candidate : PaymentResult.values()) {
                if (candidate.name().equalsIgnoreCase(result.trim())) {
                    return candidate;
                }
            }
        }
        throw ApiException.badRequest("INVALID_RESULT", "result must be one of SUCCESS, FAILED, CANCELLED");
    }

    private static String formatAmount(BigDecimal amount) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN"));
        format.setMaximumFractionDigits(Math.max(0, amount.scale()));
        return format.format(amount);
    }

    private static String esc(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value, StandardCharsets.UTF_8.name());
    }

    private static String layout(String title, String content) {
        return """
                <!doctype html>
                <html lang="vi">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%s</title>
                <style>
                  :root { color-scheme: light; --ink:#1d1b16; --muted:#6b665c; --line:#e4dfd3; --paper:#fbf9f4; }
                  * { box-sizing: border-box; }
                  body { margin:0; min-height:100vh; display:grid; place-items:center; padding:16px;
                         background:#efeadf; color:var(--ink);
                         font:16px/1.5 ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif; }
                  main { width:100%%; max-width:420px; background:var(--paper); border:1px solid var(--line);
                         border-radius:14px; padding:28px 24px; box-shadow:0 1px 0 #fff inset, 0 12px 32px -18px #0004; }
                  header { display:flex; justify-content:space-between; align-items:baseline; margin-bottom:22px; }
                  header b { font-size:18px; letter-spacing:.02em; }
                  header small { color:var(--muted); font-size:12px; border:1px dashed var(--line);
                                 padding:2px 8px; border-radius:99px; }
                  .label { margin:0; color:var(--muted); font-size:13px; text-transform:uppercase; letter-spacing:.08em; }
                  .amount { margin:2px 0 6px; font-size:34px; font-weight:700; font-variant-numeric:tabular-nums; }
                  .amount span { font-size:16px; font-weight:600; color:var(--muted); }
                  .desc { margin:0 0 22px; color:var(--muted); overflow-wrap:anywhere; }
                  form { display:grid; gap:10px; }
                  button { font:inherit; font-weight:600; padding:12px; border-radius:10px; cursor:pointer;
                           border:1px solid transparent; }
                  .ok { background:#1f6f43; color:#fff; }
                  .fail { background:#fff; color:#a12a1f; border-color:#e9c3bd; }
                  .cancel { background:transparent; color:var(--muted); border-color:var(--line); }
                  button:hover { filter:brightness(.95); }
                  .hint { margin:12px 0 0; font-size:13px; color:var(--muted); text-align:center; }
                  .state { margin:0 0 8px; overflow-wrap:anywhere; }
                  dl { margin:24px 0 0; padding-top:14px; border-top:1px solid var(--line); font-size:12px;
                       display:grid; grid-template-columns:auto 1fr; gap:4px 12px; color:var(--muted); }
                  dd { margin:0; font-family:ui-monospace, monospace; overflow-wrap:anywhere; }
                </style>
                </head>
                <body>
                <main>
                <header><b>MockPay</b><small>cổng thanh toán giả lập</small></header>
                %s
                </main>
                </body>
                </html>
                """.formatted(esc(title), content);
    }
}
