# GreyTest Admin Dashboard

Admin Dashboard tái sử dụng JWT hiện có. Tất cả endpoint bên dưới đều nằm dưới `/api/admin` và được `AdminAuthorizationInterceptor` kiểm tra tập trung: tài khoản phải đang active và có role `ADMIN`.

## Dữ liệu và quota

Flyway migration `V20__add_admin_usage_tracking.sql` tạo:

- `user_activity_log`: log yêu cầu sinh artifact, từng lượt gọi LLM Gateway và các thao tác quản trị nhạy cảm.
- `usage_quota`: quota LLM theo user, reset lười vào đầu tháng. Giá trị mặc định lấy từ `GREYTEST_DEFAULT_MONTHLY_LLM_QUOTA` (mặc định `100`).

Một `LLM_CALL` được tính ngay trước mỗi lần gọi provider, bao gồm cả lần retry. Khi hết quota, backend trả HTTP `429` với code `USAGE_QUOTA_EXCEEDED`; tác vụ nền ghi lỗi dễ hiểu vào popup Log.

## API

| Method | Endpoint | Mô tả |
| --- | --- | --- |
| GET | `/users` | Danh sách có `search`, `role`, `enabled`, `page`, `size`, `sort`, `direction` |
| GET | `/users/{id}` | Chi tiết, project, unit test và hoạt động gần đây |
| PATCH | `/users/{id}/status` | Khóa/mở khóa, body `{ "enabled": false }` |
| PATCH | `/users/{id}/role` | Đổi role, body `{ "role": "ADMIN" }` |
| PATCH | `/users/{id}/quota` | Đổi quota, body `{ "quotaLimit": 200 }` |
| GET | `/activity` | Log có `userId`, `action`, `from`, `to`, phân trang |
| GET | `/stats/overview` | Các thẻ tổng quan |
| GET | `/stats/trend` | Xu hướng, hỗ trợ `days` và `granularity=day|week|month` |
| GET | `/stats/top-users` | Xếp hạng lượt gọi LLM |
| GET | `/health` | Trạng thái các thành phần được dashboard theo dõi |

## Chạy local

```powershell
docker compose up -d postgres
mvn spring-boot:run
```

Trong frontend:

```powershell
npm install
npm run dev
```

Đăng nhập bằng tài khoản có `auth_user.role = 'ADMIN'`, sau đó mở `/admin`. Admin không thể tự khóa hoặc tự hạ quyền tài khoản đang đăng nhập.

## Kiểm thử

```powershell
mvn test
npm test -- --pool=forks --maxWorkers=1 --minWorkers=1
npm run build
```

Các integration test backend cần PostgreSQL chạy tại cổng `5433` theo `docker-compose.yml`.
