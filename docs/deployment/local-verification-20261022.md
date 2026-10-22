# GreyTest — Kết quả chuẩn bị deploy và kiểm chứng local

Ngày kiểm chứng: **22/10/2026**, Asia/Saigon. Bộ deploy đã chạy trên **Docker Linux/amd64 local**, với Caddy, Java 17 và PostgreSQL 15. **Chưa deploy AWS**; account, credit, Singapore và public HTTPS vẫn cần người dùng xác minh theo [hướng dẫn AWS Console](aws-console-steps.md).

Local đang phục vụ tại **http://127.0.0.1:18080**, project `greytest-local-20261022`, provider **mock**. Đây là stack test riêng; database/backend không publish port ra host. Backend chạy UID/GID `10001:10001`, ghi được storage/log; có restart policy, giới hạn bộ nhớ và xoay log.

## 1. Căn cứ và phiên bản

Checkpoint trước khi thêm bộ deploy:

- Backend main: `ecf125371155f36d92a98e5c5c7f57c66ed78079` ([commit](https://github.com/congy1344/KLTN_26_27_GreyTest_BE/commit/ecf125371155f36d92a98e5c5c7f57c66ed78079)).
- Frontend main: `82e0d42a922da3b18e0a36292425bc2142bc9207` ([commit](https://github.com/congy1344/KLTN_26_27_GreyTest_FE/commit/82e0d42a922da3b18e0a36292425bc2142bc9207)).
- Hai repo đã tách nhánh `dev` trước khi áp dụng các file deploy. Tại thời điểm viết báo cáo, thay đổi deploy đang chờ commit/push cuối; không dùng checkpoint main làm commit release của bộ deploy.

Nguồn đối chiếu là các bản tóm tắt ngoài Git tại workspace:

- `outputs/aws-local-20261022/source-sync-verification.md`: kiểm thử baseline backend và source synchronization.
- `outputs/aws-local-20261022/actual-apply-verification.md`: cấu hình/profile deploy đã áp dụng trên đường dẫn repo thực.
- `outputs/aws-local-20261022/runtime-verification.md`: image build, pipeline, persistence và backup/restore local độc lập.

Báo cáo này chỉ giữ kết quả đã loại secrets. Không đưa env thật, fixture credentials, token, API key, private key hoặc database backup vào repo.

## 2. Test và cấu hình

| Kiểm tra | Kết quả | Phạm vi |
| --- | --- | --- |
| Full backend baseline | **690 tests**, 0 failure, 0 error, **3 skip**; **687 thực thi** | Patch source synchronization cuối, DB test mới tách biệt, JDK 17/Maven 3.9.9, mock LLM; trước khi thêm bộ deploy |
| Frontend baseline | **190 tests đạt**, production build đạt | Chứng cứ baseline; source ứng dụng frontend không đổi trong đợt deploy |
| `AwsDeploymentProfileTest` trên file thực | **2 tests đạt**, 0 failure/error/skip | Kiểm tra profile AWS riêng; không cộng vào baseline để gọi là một full suite mới |
| Compose env bắt buộc | **Đạt** | Thiếu/để trống DB hoặc JWT secret bị từ chối |
| Port và volume isolation | **Đạt** | DB/backend private; project restore có volume khác production |
| Env ignore | **Đạt** | `.env.aws`, `.env.production` bị Git bỏ qua; file example được phép |
| Caddy config và smoke script | **Đạt** | Validate site HTTP local và placeholder hostname; Python smoke đúng cú pháp |
| Backend/frontend diff check | **Đạt** | Không lỗi whitespace tại lần kiểm tra áp dụng |

Dockerfile backend package với `-DskipTests`; kết quả Docker build không được dùng thay cho test suite ở bảng trên.

## 3. Linux container và luồng ứng dụng

Backend `Dockerfile.aws` và frontend `Dockerfile` được build bằng `docker buildx build --platform linux/amd64 --load` từ các file thực. Docker Desktop Linux 29.1.3 chạy PostgreSQL 15 Alpine và Caddy 2 Alpine. Provider đang chạy được kiểm tra là **mock** trước khi gọi generation.

| Kiểm tra runtime | Kết quả |
| --- | --- |
| Backend và frontend Linux/amd64 image build | **Đạt** |
| Cold start, PostgreSQL/backend health | **Đạt** |
| Proxy `/api/ping`, header `nosniff` | **Đạt** |
| Refresh URL sâu React | **Đạt** |
| Protected API lỗi vẫn trả JSON | **Đạt** |
| Payment tại edge, kể cả matrix parameter trong path | **503 / PAYMENT_DISABLED**, đúng cấu hình demo |
| Register/login | **Đạt** |
| ZIP upload và static analysis | **Đạt**; fixture nhỏ có 1 production class/method, 1 existing-test file, không parse failure |
| Manual BR approval → mock plan → approval → mock case → approval → mock unit test | **Đạt** |
| JaCoCo XML, history và traceability | **Đạt** |
| Download ZIP unit test và export JSON/Markdown | **Đạt** |
| Tài khoản khác owner đọc hoặc mutation | **403**, đúng phạm vi kiểm tra |
| Force-recreate backend/web | **Đạt**; login, metadata, source, coverage và artifact được giữ |
| Container OOM trong các kiểm tra đã chạy | Không phát hiện OOM-killed |

Các unit test sinh ra không được thực thi trên server. Đây là kiểm tra đóng gói, pipeline và persistence với mock, không đánh giá chất lượng test do AI thật sinh.

Trong verifier có một lần so metadata trước export với sau export. ExportService chủ động chuyển `COVERAGE_ANALYZED` sang `COMPLETED`; baseline đã được lấy lại sau export và đối chiếu toàn bộ lần nữa. Không đổi hành vi ứng dụng hoặc nới kiểm tra persistence để đạt kết quả.

## 4. Backup và restore tách biệt

- Tạm dừng mutation để tạo **PostgreSQL dump + toàn bộ storage cùng thời điểm**; `pg_dump -Fc` và kiểm tra dump TOC đạt. Storage và Caddy data/config được đóng gói riêng.
- Restore vào project **`greytest-restore-20261022`**, chỉ bind loopback **28080/28443**; không ghi đè volume của project local gốc.
- Login, pipeline artifact, download/export và coverage sau restore khớp baseline.
- **Bốn file source/coverage có SHA256 trước và sau restore giống nhau**. Flyway đã xác minh **V26 hợp lệ**.
- Khởi động lại project local gốc đạt health và HTTP smoke. Restore containers đã dừng; các volume test được giữ để kiểm tra.

Các thao tác này chứng minh khôi phục local của fixture đã kiểm tra. Chưa xác minh chuyển backup ra/vào AWS hoặc phục hồi sau mất EC2/EBS thực tế. Quy trình vận hành nằm trong [runbook](aws-runbook.md).

## 5. RAM và giới hạn suy luận

Một mẫu đo lúc idle sau fixture pipeline nhỏ: backend **383,5 MiB**, PostgreSQL **60,25 MiB**, Caddy **11,97 MiB**, tổng khoảng **456 MiB**. Đây không phải đỉnh RAM. Giới hạn thử của container lần lượt là 1152/256/128 MiB.

**Chưa đủ căn cứ khẳng định `t3.small` 2 GiB đáp ứng GreyTest.** Cần đo project đại diện, parsing/generation thực tế, provider latency/concurrency, overhead hệ điều hành EC2 và CPU credits trên máy cloud.

## 6. Dependency audit và các khoản cần xử lý sau

Báo cáo npm của frontend đã được ghi nhận:

| Phạm vi audit | Tổng | Moderate | High | Critical |
| --- | --- | --- | --- | --- |
| Toàn bộ dependencies | 23 | 8 | 13 | 2 |
| `--omit=dev` | 4 | 2 | 2 | 0 |

Lần audit đầu bị automatic approval review từ chối vì gửi danh sách dependency tới npm. Sau khi người dùng cho phép, audit đã chạy xong và có hai báo cáo trên; **không còn bị chặn ở bước xin phép audit**.

Reviewer đã đối chiếu advisory với đường thực thi đã đọc và **chưa thấy blocker khai thác được qua Caddy/static frontend hiện tại**. Đây là kết luận trong phạm vi các đường đã kiểm tra, không phải bảo đảm toàn hệ thống an toàn. Các bản vá dưới đây là nợ đã phân loại, chưa được áp dụng trong task deploy:

| Gói đang dùng | Phân loại theo đường thực thi đã review | Hướng xử lý riêng |
| --- | --- | --- |
| `axios@1.18.0` — npm high | Browser đang dùng XHR; nhóm lỗi Node HTTP/2/proxy/data URL không nằm trên đường này. Gadget serialization cần prototype pollution ở nơi khác, chưa thấy primitive tương ứng trong phần đã đọc. [Node-only advisory](https://github.com/axios/axios/security/advisories/GHSA-c29m-xwm3-cm6r), [precondition](https://github.com/axios/axios/security/advisories/GHSA-x97p-jq2g-jp4f). | Patch `1.20.0`, trong range `^1.7.7`; cập nhật lockfile rồi test browser/API/multipart riêng. |
| `follow-redirects@1.16.0` — npm high | Nằm trong Node HTTP adapter; Axios browser map adapter này thành null. Advisory cần request/redirect qua Node. [Advisory](https://github.com/follow-redirects/follow-redirects/security/advisories/GHSA-q3jh-wrc6-jgq8). | Patch `1.16.1`, trong range transitive hiện tại. |
| `react-router-dom@6.30.4` — moderate | Open redirect/XSS cần target navigation không tin cậy; các path đã đọc là nội bộ cố định/numeric hoặc được encode, chưa thấy target raw tương ứng. [Advisory](https://github.com/remix-run/react-router/security/advisories/GHSA-jjmj-jmhj-qwj2). | Patch `6.30.6`, trong range `^6.27.0`; test navigation/deep link. |
| `react-router@6.30.4` — moderate | Không dùng SSR/hydration; BrowserRouter declarative nằm ngoài điều kiện SSR advisory. Nhóm backslash redirect vẫn cần target không tin cậy. [SSR exclusion](https://github.com/remix-run/react-router/security/advisories/GHSA-337j-9hxr-rhxg). | Các advisory được review có fix `7.18.0`, ngoài major hiện tại; không tự nâng major trong task deploy. |
| `vitest@2.1.9`, `tinypool@1.1.1` — nhóm dev có critical | Không nằm trong Caddy runtime image. Đường Vitest cần UI/API server bị expose; đường Tinypool được review cần prototype pollution từ nơi khác. [Vitest advisories](https://github.com/vitest-dev/vitest/security/advisories), [Tinypool precondition](https://github.com/tinylibs/tinypool/security/advisories/GHSA-85c8-ppgw-ccpr). | Tách đợt nâng dev tooling; không override Tinypool major dưới Vitest 2 chỉ để xóa cảnh báo. |

Chưa sửa package/lockfile, chưa chạy exploit probe hoặc audit toàn bộ repo trong đợt phân loại này. Audit **vẫn còn 23/4 findings**, không phải zero. Nếu thực hiện đợt vá riêng, cần chạy lại frontend tests/build/audit và kiểm tra login, upload multipart, API lỗi/401, navigation và deep link qua Caddy.

## 7. Những việc còn lại trước nghiệm thu AWS

- Xác minh Free plan/credit/expiry và region Singapore thực tế; account signup mới có thể chỉ cho một region khác, không tự nâng Paid/advanced features để đi tiếp.
- Tạo EC2/IAM/SSM/SG/EBS, DNS và public HTTPS; kiểm tra cấp/renew certificate và reboot EC2.
- Đo workload và chi phí quy đổi credit trên EC2; kiểm tra một job provider thật trong quota riêng.
- Đối chiếu toàn bộ luồng demo và phân quyền trên cloud; backup ra ngoài VM và restore theo inventory thực tế.
- Kiểm chứng rollback với một release production cũ tương thích schema. Hiện chưa có artifact production cũ để nghiệm thu bước này; recreate và restore local không thay thế kiểm tra đó.
- Ghi commit/tag release cuối cùng và cập nhật evidence theo đúng hai commit đã build; xử lý nợ dependency theo một đợt vá có kiểm tra riêng.

Không có tài nguyên AWS nào được tạo trong đợt kiểm chứng này. Bước tiếp theo của người dùng là [thực hiện checklist AWS Console](aws-console-steps.md), bắt đầu bằng kiểm tra plan, credit và region.
