# Kiểm thử tích hợp chương 5

## Phạm vi và cách chạy

`Chapter5PipelineIntegrationTest` chạy Spring Boot + MockMvc qua controller, xác thực JWT,
service, repository, Flyway, PostgreSQL và storage thật. Chỉ `LlmClient` được thay bằng
provider fixture có JSON hợp lệ, lấy ID từ context trong prompt. Gateway, parser và semantic
validator thật vẫn chạy. Test không gọi provider bên ngoài và không đo độ chính xác AI.

Yêu cầu JDK 21, Maven và cluster PostgreSQL kiểm thử riêng tại `127.0.0.1:55439`,
role `chapter5_test`, database **`chapter5_integration`** đã tạo. Database này được dùng
riêng cho suite; không chạy suite trên database ứng dụng. Storage là thư mục tạm riêng.
Các property trên annotation có độ ưu tiên cao; `.env` không được import vào context này.

```powershell
# Chạy tại backend; không chạy song song với Maven khác trên cùng target.
mvn -o '-Dtest=Chapter5PipelineIntegrationTest,GenerationProgressControllerTest' test
```

Fixture ZIP gồm một service Spring Boot `sample.PriceService`, một method
`discount(int)`, một test có sẵn cho amount 99 và file `pom.xml`.
Yêu cầu độc lập: amount 100 trả về discount 10. Provider fixture tạo Plan/Case
boundary và Unit Test dùng `new PriceService()` + `assertEquals(10, sut.discount(100))`.
Suite biên dịch source/test bằng compiler của JDK và thực thi chính method test tải từ API.
Đây là kiểm chứng wiring và giữ nguyên kỳ vọng; không đánh giá khả năng suy luận của AI.

## Ánh xạ các nhóm IT

| Mã | Method test | Input và kỳ vọng |
| --- | --- | --- |
| IT-01 | `it01UploadAnalyzeSeparatesProductionAndExistingTests` | ZIP hợp lệ; upload 201, analyze 200, manifest chỉ 1 production class/1 method, 1 existing test riêng; DB và storage khớp |
| IT-02 | `it02ApprovedRulesDrivePersistedPlanCaseAndExecutableAssertion` | BR chưa duyệt: job FAILED, không Plan; sau duyệt: 202/QUEUED → COMPLETED từng stage; link BR→Plan→Case→Unit đúng; kỳ vọng 10 và input 100; generated test compile/chạy đạt; download ZIP |
| IT-03 | `it03CoverageHistoryTraceabilityAndExportsPreserveStoredArtifacts` | Hai XML counter kiểm soát: line 50% rồi 100%; round/history 2, previous 50%; requirement 100% theo 1 BR có Case; trace đủ chuỗi; JSON/Markdown chứa artifact đã lưu |
| IT-04 | `it04UnauthorizedReadsMutationsAndPauseCannotChangeOwnerState` | User khác đọc/sửa 403; pause thiếu/sai token 401, cross-owner 403; progress và BR trước/sau không đổi; owner pause 200/PAUSED |
| IT-05 | `it05ProviderFailureAndDuplicateJobKeepApprovedRuleAndNoPartialPlans` | Provider bị chặn bằng latch; job cùng project và mutation nhận 409; provider lỗi không retry → FAILED; BR approved và trạng thái BR_APPROVED giữ nguyên; không partial Plan |

XML IT-03 là fixture counter, không phải số đo JaCoCo từ chạy generated test.
Requirement coverage 100% chỉ trên một BR fixture, không đại diện chất lượng hệ thống.

## Bất đồng bộ và cleanup

Suite không có transaction rollback toàn test. Fixture được tạo qua API và commit trước
khi worker chạy thread khác. Poll terminal `COMPLETED`/`FAILED`/`PAUSED` có timeout 20 giây.
Trường hợp duplicate dùng latch để giữ provider trong trạng thái đang xử lý, không phụ thuộc
vào `sleep` cố định. Cleanup nhả latch, đợi project mutation lock được nhả tối đa 15 giây,
xóa project qua API theo ID đã tạo rồi xóa user theo ID, cuối suite xóa storage tạm.

## Regression bảo mật

Pause trước đây bỏ qua auth/owner khi thiếu Authorization. Endpoint nay yêu cầu header
và luôn kiểm tra quyền trước gọi job/progress service. Handler hiện hữu trả 401 khi thiếu
Authorization. `GenerationProgressControllerTest.pauseRequiresAuthorizationBeforeChangingJob`
kiểm tra không gọi service sửa trạng thái; IT-04 kiểm tra thêm token sai/cross-owner trên DB thật,
giữ nguyên snapshot job RUNNING khi bị từ chối và owner tạm dừng được sang PAUSED.

## Kết quả và bằng chứng

Lần cuối **09/10/2026 19:54:23 (UTC+7)**: **9/9 Pass**, gồm 5 IT và 4 controller test,
0 failure/error/skipped. Maven build thành công sau 24,727 giây; đây là thời gian suite,
không phải latency API. Runtime JDK 23 với target project Java 21, PostgreSQL 17.6.

Bằng chứng tại `outputs/chapter-5/integration-20261009`: `test-results-final.csv`
giữ sáu cột chuẩn và mapping method test; `metadata.json` ghi revision parent và SHA256
các source đang dirty; `raw/final/*.xml`, `raw/maven-final-sequenced.log` giữ kết quả.
Snapshot project theo từng case ở `snapshots/run-1791550450412`, tên file có request sequence
để không đè snapshot trước. Recorder không ghi auth response/header.

Không xóa lần thất bại: lần đầu 4/9 Fail do fixture tạo BR thiếu `sourceBranchId=TERNARY-1`;
retest1 1/9 Fail do fixture kiểm tra pause khi chưa có job; retest2 và hai lần kiểm chứng evidence
đều 9/9 Pass. Các lỗi này là setup/assertion test đã sửa theo contract hiện hữu, không thay đổi
business logic. Log/XML ban đầu đã thay JWT/password fixture bằng nhãn redaction.

Các case không thay thế System Test, UAT hoặc thực nghiệm provider thật.

## Tái sử dụng server fixture

`Chapter5FixtureServer` là main dưới `src/test`, không nằm trong JAR sản phẩm. Helper
khởi động cùng application và provider fixture trên `127.0.0.1:18081`, dùng database riêng
**`chapter5_system`** (phải tạo trước), storage `../outputs/chapter-5/system-20261009/storage`.
`.env` bị tắt, provider fixture được chọn primary; không gọi LLM thật. Helper cố định config
kiểm thử và không nhận override CLI để tránh vô tình dùng database ứng dụng.

Sau `test-compile`, lấy Java classpath từ property `java.class.path` trong Surefire XML rồi chạy:

```powershell
[xml]$chapter5Report = Get-Content -Raw ../outputs/chapter-5/integration-20261009/raw/final/TEST-com.greytest.Chapter5PipelineIntegrationTest.xml
$chapter5Classpath = ($chapter5Report.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
java -cp $chapter5Classpath com.greytest.Chapter5FixtureServer
```

Server dùng đúng fixture `sample.PriceService.discount(int)` và workflow **user nhập BR**;
fixture provider chỉ có các stage Plan, Case và Unit Test. Không dùng helper để đánh giá
generation trên source khác, AI auto-BR/review hoặc chất lượng AI. Server mới được biên dịch
trong suite trên; chưa chạy browser/System Test trong Phase 3.
