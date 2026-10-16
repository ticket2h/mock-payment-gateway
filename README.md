# Ticket2h mock-payment-gateway (MockPay)

[![CI](https://github.com/ticket2h/mock-payment-gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/ticket2h/mock-payment-gateway/actions/workflows/ci.yml)

Cổng thanh toán giả lập cho Ticket2h, giống VNPay/MoMo nhưng chạy local, có **chế độ chaos** để thử tình huống xấu:
webhook gửi trùng, gửi trễ, mất webhook, webhook tới trước khi khách quay lại, merchant lỗi mấy lần đầu, hoàn tiền
thất bại. Spring Boot 4.1, Java 25. Cổng 9090. Không có database: dữ liệu nằm trong bộ nhớ, tắt là mất.

Đặc tả API và webhook: [`docs/mockpay-spec.md`](docs/mockpay-spec.md).

## Chạy

Cần `MOCKPAY_API_KEY` và `MOCKPAY_WEBHOOK_SECRET` giống hệt bên core-service (hướng dẫn từ máy trống:
[infra/docs/chay-tren-may-moi.md](https://github.com/ticket2h/infra/blob/main/docs/chay-tren-may-moi.md)).

```bash
./mvnw spring-boot:run      # :9090
```

Service đọc secret từ `../infra/.env` nếu repo [infra](https://github.com/ticket2h/infra) nằm cạnh repo này, từ `.env` của chính repo
(mẫu: [`.env.example`](.env.example), chỉ gồm biến service này cần), hoặc từ biến môi trường của máy. Repo infra sinh cả hai giá trị bằng `scripts/init-env.sh`.

Image Docker: `ghcr.io/ticket2h/mock-payment-gateway` (CI build mỗi lần push lên `main`). Build tại máy: `docker build -t mock-payment-gateway .`
(không cần JDK). Chạy cả hệ thống từ image: `docker compose --profile apps up -d` trong repo infra.

Bật chaos (áp dụng cho phiên thanh toán tạo **sau** lệnh này):

```bash
curl -X PUT localhost:9090/admin/chaos -H 'Content-Type: application/json' -d '{"duplicateDeliveries":3}'
curl -X DELETE localhost:9090/admin/chaos      # về mặc định
```

## Test

```bash
./mvnw verify      # 48 test, không cần Docker
```
