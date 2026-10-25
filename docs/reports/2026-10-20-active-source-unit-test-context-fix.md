# Sửa context Unit Test thiếu package/import ở analysis cũ

Ngày: 20/10/2026. Project kiểm chứng: 248, `piggymetrics`, active revision 90.

## Nguyên nhân đã xác minh

Log backend lúc `2026-10-20T03:25:23.018Z` và các lần thử tiếp theo báo không resolve được `Account` trong `AccountServiceImpl`.

DB chứa cả `com.piggymetrics.account.domain.Account` và `com.piggymetrics.statistics.domain.Account`, nhưng `java_class.source_code` của Service và các domain class là dữ liệu cũ thiếu package/import. Snapshot active vẫn có source đầy đủ, trong đó Service import chính xác `com.piggymetrics.account.domain.Account`.

Luồng source-update đã có `buildSnapshotUnitTestContext`; luồng Unit Test thông thường vẫn gọi `buildUnitTestContext` trực tiếp từ analysis DB. Vì vậy bản sửa snapshot trước đây chưa bao phủ đường gọi đang lỗi. Không thay đổi quy tắc nghiệp vụ của project Java đầu vào.

## Bản sửa

- `GenerationContextBuilder` lấy đường dẫn source active từ `Project.storagePath` và dùng `SnapshotAnalysisLoader` dựng analysis trong bộ nhớ cho cả sinh toàn bộ và sinh theo nhóm Test Case.
- Context lấy package, imports, source class/method và dependency từ source thật; giữ ID class/method và các BR/Plan/Case đã duyệt. Không ghi lại artifact, không gọi analyze để xóa/tạo lại analysis.
- Snapshot ứng viên truyền tường minh vẫn được dùng riêng cho source-update, không bị thay bằng snapshot active.
- Giữ khả năng phân tích một phần của luồng thông thường: file không parse được ở phần không liên quan không chặn target hợp lệ. Thiếu target hoặc dependency cần dùng thì báo lỗi; không dùng source DB cũ thay thế.
- Kiểm tra identity theo file/module để không dùng declaration cùng FQN ở module khác thay cho file đang lỗi. Guard nhận biết file khai báo của nested type. Chính sách parse nghiêm ngặt của candidate snapshot vẫn giữ nguyên.
- Không thêm dependency, không sửa schema, không sửa production source `piggymetrics` hoặc dữ liệu project 248.

## Kiểm chứng

Regression test mới: `ActiveSourceUnitTestContextIntegrationTest`, 10 trường hợp chạy với Spring/repository thật và transaction rollback:

1. Sinh toàn bộ: chọn đúng Account theo active imports.
2. Sinh theo nhóm: cùng kết quả, giữ ID và artifact hiện có.
3. Target mất khỏi active snapshot: báo lỗi.
4. Candidate snapshot có import khác: dùng đúng candidate.
5. File không parse được ở module không liên quan: tiếp tục.
6. Account cần dùng không parse được: báo lỗi.
7. File cùng FQN ở module khác bị lỗi: vẫn dùng dependency hợp lệ ở module target.
8. Nested dependency lỗi: báo đúng file khai báo bên ngoài.
9. Target lỗi nhưng module khác có cùng FQN: không gán nhầm ID/source.
10. Dependency tại module target lỗi nhưng module khác có cùng FQN: không thay bằng API của module khác.

Đã quan sát test fail trước các sửa tương ứng, gồm lỗi `Cannot resolve referenced production type 'Account'` ban đầu. Không giảm assertion hoặc bỏ qua các regression test mới.

Toàn bộ backend: **611 tests, 610 pass, 0 failures, 0 errors, 1 skip**. Test PetClinic golden có sẵn cần checkout ngoài nên được skip theo cấu hình hiện hành. Lệnh dùng Maven 3.9.14 với ByteBuddy agent của bộ test có sẵn:

```powershell
mvn '-DargLine=-javaagent:C:/Users/KhaiTien/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar' test
```

Smoke test chỉ đọc trên artifact thật project 248 và bản copy snapshot active:

- 6 Test Case `1658–1663`: context chứa Account ở `account-service`, không chứa Account ở `statistics-service`; giữ Service ID 6278.
- Toàn bộ 50 Test Case approved: dựng context thành công, gồm 24 class cần thiết.
- Hash dữ liệu project/class/method/BR/Plan/Case/Unit Test trước và sau giống nhau.
- Smoke test chỉ ánh xạ đường dẫn snapshot từ Docker sang bản copy local; dùng AnalysisService, context builder và repository thật. Không gọi LLM, không tiêu quota.
- Reviewer độc lập đã kiểm tra bản sửa cuối và không còn phát hiện actionable finding trong phạm vi này.

Log tại workspace gốc:

- `.greytest-generation-verification/active-source-context-red.log`
- `.greytest-generation-verification/active-source-partial-red.log`
- `.greytest-generation-verification/active-source-dependency-red.log`
- `.greytest-generation-verification/active-source-identity-red.log`
- `.greytest-generation-verification/active-source-local-identity-red.log`
- `.greytest-generation-verification/active-source-full-tests-final.log`
- `.greytest-generation-verification/active-source-smoke/final.log`
- `.greytest-generation-verification/active-source-docker-build.log`

Backend Docker đã build và khởi động lại bằng source đã kiểm tra, giữ PostgreSQL và các volume hiện có. Chưa trigger sinh AI thật hoặc compile/run Unit Test do LLM sinh cho `piggymetrics`; kết quả kiểm chứng trên là việc dựng context và regression của GreyTest, không phải tuyên bố mọi output LLM đều hợp lệ.
