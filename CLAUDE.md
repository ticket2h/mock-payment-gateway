# Ticket2h mock-payment-gateway

MockPay: cổng thanh toán giả lập (cổng 9090) cho core-service của Ticket2h. Đặc tả: `docs/mockpay-spec.md`.
Một trong các repo của hệ thống (cạnh `../infra`, `../core-service`).

## Lệnh
- Build và test: `./mvnw verify` (48 test, không cần Docker)
- Chạy: `./mvnw spring-boot:run` (đọc `MOCKPAY_API_KEY`, `MOCKPAY_WEBHOOK_SECRET` từ `../infra/.env` hoặc `.env`)
- Image: `docker build -t mock-payment-gateway .`

## Quy tắc
- API, định dạng webhook, cách ký HMAC là hợp đồng với core-service: đổi thì sửa `docs/mockpay-spec.md` và core-service cùng lúc.
- Test vector chữ ký (`WebhookSignerTest`) là dữ liệu cố định: không sửa chuỗi đầu vào hay chữ ký mong đợi.
- Không log secret ký webhook hay API key.
- Không thêm dependency mới nếu chưa hỏi.
- `Dockerfile`, `.github/workflows/ci.yml` giống nhau ở cả 4 repo service (ADR-014 trong `../infra/docs/adr/`): sửa một thì sửa cả bốn.
  Thêm biến môi trường mới thì thêm vào `.env.example` ở đây và `../infra` (`.env.example`, `scripts/init-env.sh`, `docker-compose.yml`).
