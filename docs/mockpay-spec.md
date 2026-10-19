# MockPay: spec cổng thanh toán giả lập

Nguồn: mục "Spec MockPay" ở Bước 8 của `ticketrush-m2-m3-huong-dan.md`, bổ sung các điểm mà core-service dựa vào (mục cuối).

## Spec

Module `mock-payment-gateway`, Spring Boot, cổng 9090, lưu dữ liệu trong bộ nhớ (`ConcurrentHashMap`).

**Xác thực merchant:** mọi API `/api/**` yêu cầu header `X-Api-Key`.

**1. Tạo phiên thanh toán:** `POST /api/v1/payments`

```json
{
  "merchantRef": "2b1f...",
  "amount": 4600000,
  "currency": "VND",
  "description": "Ticket2h order c0de...",
  "returnUrl": "http://localhost:5173/payments/return?orderId=7c9e...",
  "notifyUrl": "http://localhost:8080/webhooks/payments/mockpay",
  "expiresInSeconds": 900
}
```

Trả `201`:

```json
{ "gatewayPaymentId": "mp_8f3a...", "paymentUrl": "http://localhost:9090/pay/mp_8f3a...", "expiresAt": "..." }
```

Gọi lại với cùng `merchantRef` → trả lại phiên cũ (idempotent).

**2. Trang thanh toán:** `GET /pay/{gatewayPaymentId}`: HTML đơn giản, hiện số tiền và ba nút **Thành công**, **Thất bại**, **Huỷ**. Bấm nút → `POST /pay/{id}/complete` với `result`:

- Tạo `gatewayTransactionId` (khi thành công)
- Lên lịch gửi webhook theo cấu hình chaos
- Chuyển trình duyệt về `returnUrl?merchantRef=...&status=SUCCESS`. Tham số này **cố ý không ký**: merchant không được tin nó

**3. Hoàn tất không cần trình duyệt** (cho test và script): `POST /admin/payments/{gatewayPaymentId}/complete?result=SUCCESS|FAILED`

**4. Webhook gửi tới `notifyUrl`:**

```http
POST /webhooks/payments/mockpay
Content-Type: application/json
X-MockPay-Event-Id: evt_51c2...
X-MockPay-Timestamp: 1793534400
X-MockPay-Signature: 9f2c...(hex)

{
  "eventId": "evt_51c2...",
  "type": "payment.succeeded",
  "gatewayPaymentId": "mp_8f3a...",
  "gatewayTransactionId": "tx_77d1...",
  "merchantRef": "2b1f...",
  "amount": 4600000,
  "currency": "VND",
  "occurredAt": "2026-11-01T12:03:00Z"
}
```

- `type`: `payment.succeeded` hoặc `payment.failed`
- `X-MockPay-Timestamp`: số giây kể từ epoch, lúc gửi
- `X-MockPay-Signature` = `hex(HMAC_SHA256(webhookSecret, timestamp + "." + rawBody))`, tính trên **đúng chuỗi byte được gửi đi**
- Cùng một sự kiện gửi lại thì giữ nguyên `eventId`
- Merchant trả mã khác 2xx hoặc quá 5 giây → thử lại sau 2, 4, 8, 16, 32 giây, tối đa 6 lần

**5. Truy vấn giao dịch:** `GET /api/v1/payments/by-ref/{merchantRef}` → trạng thái, `gatewayTransactionId`, số tiền.

**6. Hoàn tiền:** `POST /api/v1/refunds`

```json
{ "gatewayTransactionId": "tx_77d1...", "amount": 4600000, "idempotencyKey": "2b1f..." }
```

Trả `{ "refundId": "rf_...", "status": "SUCCEEDED" }`. Cùng `idempotencyKey` → trả lại kết quả cũ, không hoàn hai lần.

**7. Chaos:** `PUT /admin/chaos`, áp dụng cho các phiên tạo sau đó:

```json
{
  "webhookDelayMs": 0,
  "duplicateDeliveries": 1,
  "dropWebhook": false,
  "webhookBeforeRedirect": false,
  "failFirstDeliveries": 0,
  "refundFailureRate": 0.0
}
```

| Trường | Tác dụng |
|---|---|
| `webhookDelayMs` | Chờ bao lâu mới gửi webhook (ví dụ 30000) |
| `duplicateDeliveries` | Gửi mỗi webhook bao nhiêu lần, **song song** (ví dụ 3) |
| `dropWebhook` | Không bao giờ gửi webhook |
| `webhookBeforeRedirect` | Gửi webhook trước khi chuyển trình duyệt về |
| `failFirstDeliveries` | Giả vờ N lần gửi đầu bị lỗi mạng (để thử cơ chế gửi lại) |
| `refundFailureRate` | Tỉ lệ API hoàn tiền trả 503 |

`GET /admin/chaos` xem cấu hình hiện tại, `DELETE /admin/chaos` về mặc định.

## Hợp đồng mà core-service của Ticket2h dựa vào

| Mục | Quy ước |
|---|---|
| Cấu hình | `server.port=9090`; `mockpay.api-key=${MOCKPAY_API_KEY}`; `mockpay.webhook-secret=${MOCKPAY_WEBHOOK_SECRET}`; `mockpay.public-base-url=http://localhost:9090`. Biến môi trường đọc từ `../infra/.env` (khi repo infra nằm cạnh), `.env` ở gốc repo, hoặc biến môi trường của máy (`spring.config.import: optional:file:../infra/.env[.properties],optional:file:.env[.properties]`) |
| Xác thực | `/api/**` yêu cầu `X-Api-Key` đúng, sai hoặc thiếu → `401`. `/admin/**` và `/pay/**` không cần key (chỉ dùng local) |
| Tạo phiên | `merchantRef` là chuỗi (Ticket2h gửi UUID của payment). `amount` là số JSON có thể có phần thập phân (`4600000.00`). Lần đầu `201`, gọi lại cùng `merchantRef` → `200` với cùng body |
| Webhook | Body JSON đúng các trường ở mục 4. `amount` giữ nguyên giá trị đã nhận khi tạo phiên. `gatewayTransactionId` là `null` với `payment.failed`. Bấm **Huỷ** cũng gửi `payment.failed` |
| Hoàn tiền | `POST /api/v1/refunds` trả `200`. `gatewayTransactionId` không tồn tại → `404`. Chaos lỗi → `503` |
| `PUT /admin/chaos` | Body có thể chỉ chứa một phần các trường, trường thiếu nhận giá trị mặc định |
