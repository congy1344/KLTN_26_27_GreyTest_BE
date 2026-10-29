# Mockito compatibility và Maven fallback của coverage runner

Ngày kiểm chứng: 20/10/2026.

## Nguyên nhân và bản sửa

1. Prompt và semantic validator trước đây luôn yêu cầu `verifyNoInteractions`, đồng thời từ chối `verifyZeroInteractions` mà không xét build của project đầu vào. Hiện `ProjectJavaVersionDetector` đọc version Mockito từ Maven/Gradle, property và các build ancestor của module. `AIAgentService` truyền cùng thông tin version vào prompt và semantic validator, cho cả source active và candidate snapshot.
2. Coverage ZIP trước đây luôn chèn `src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker` với alias `mock-maker-subclass`. Đã bỏ việc tạo resource này cho mọi phiên bản; giữ cấu hình/default của project. Không nâng dependency và không ghi đè cấu hình Mockito của người dùng.
3. Script Windows trước đây tìm Maven qua `where mvn` sau đoạn junction, không dò công cụ workspace. Hiện script thêm đường dẫn tuyệt đối `.tools/apache-maven-*/bin` vào PATH ngay đầu file, tìm tại module và hai cấp cha. Thao tác này diễn ra trước cài parent POM, tạo junction và gọi script con, nên script con kế thừa PATH.

## Ma trận API

| Mockito | verifyZeroInteractions | verifyNoInteractions | verifyNoMoreInteractions |
| --- | --- | --- | --- |
| 2.x | Cho phép | Chặn cả call và static import | Cho phép |
| 3.0.0 | Cho phép | Chặn | Cho phép |
| 3.0.1 / 3.9 | Cho phép | Cho phép | Cho phép |
| 4.x / 5.x | Chặn cả call và static import | Cho phép | Cho phép |
| Chưa xác định / module xung đột | Chặn | Chặn | API portable được hướng dẫn sử dụng |

Đã đối chiếu ranh giới 3.0.0/3.0.1 với source chính thức: API chưa có trong [Mockito 3.0.0](https://raw.githubusercontent.com/mockito/mockito/v3.0.0/src/main/java/org/mockito/Mockito.java), có trong [Mockito 3.0.1](https://raw.githubusercontent.com/mockito/mockito/v3.0.1/src/main/java/org/mockito/Mockito.java).

Detector ưu tiên version dependency tường minh/property override trước mặc định Boot 2.0/2.1. Profile chưa xác định activation hoặc version chưa resolve được được coi là unknown; dependency của plugin không được coi là dependency test của project. Khi batch trộn module/dependency có cùng major nhưng khác khả năng API (3.9 và 3.0.0), detector cũng trả unknown để dùng API chung.

## Kiểm chứng đã chạy

**Thêm 33 regression test**, bao gồm:

- Pipeline đọc build thật, nhận response mock LLM dùng API sai rồi yêu cầu retry; kiểm tra response cuối tương thích Mockito 2.15, 3.0.0, 3.0.1, 3.9 và 5.11.
- Ma trận semantic validation theo version, kiểm tra static import sai dù chưa được gọi.
- Maven parent/property/module override, Gradle literal/property, Boot 2.0/2.1, active/candidate, profile, plugin và module/dependency xung đột API.
- ZIP không chứa resource MockMaker tự sinh.
- Chạy script `.cmd` thật qua `cmd.exe` trong đường dẫn ASCII và Unicode. PATH ban đầu chỉ có công cụ Windows, Maven nằm riêng trong `.tools`. Maven stub xác nhận đã được tìm thấy; trường hợp Unicode xác nhận script con chạy với `GREYTEST_JUNCTION_RUN=1`. Các assertion kiểm tra exit code, report file và thông báo thành công. Stub cô lập riêng việc dò Maven/junction, không gọi Maven tải dependency.

**Toàn bộ backend:** 644 tests, **643 pass, 0 failure, 0 error, 1 skip**. Test skip là PetClinic golden đã có sẵn, cần checkout ngoài. Sáu test cũ kiểm tra tương tác Mockito nay nêu rõ fixture dùng Mockito 5.11; assertion hành vi được giữ nguyên.

Lệnh Maven 3.9.14 đã chạy:

```powershell
mvn '-DargLine=-javaagent:C:/Users/KhaiTien/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar' test
```

**Kiểm chứng compiler độc lập:** dùng javac với các jar Mockito 2.15.0, 3.9.0, 4.11.0 và 5.11.0 thật trong Maven cache, kiểm tra 12 tổ hợp static import/lời gọi API. Các tổ hợp hợp lệ compile thành công, các API không tồn tại bị compiler từ chối đúng dự kiến. Không dùng class Mockito giả để chứng minh compatibility.

Reviewer độc lập đã kiểm tra bản sửa cuối và không còn actionable finding trong phạm vi này.

Backend Docker đã build và khởi động lại; `/api/ping` qua cổng 8080 và proxy 3000 đều trả 200. PostgreSQL và các volume dữ liệu được giữ nguyên.

## Log và giới hạn

Log ở workspace gốc:

- `.greytest-generation-verification/mockito-coverage-red.log`
- `.greytest-generation-verification/mockito-profile-red.log`
- `.greytest-generation-verification/mockito-api-boundary-red.log`
- `.greytest-generation-verification/mockito-mixed-api-red.log`
- `.greytest-generation-verification/mockito-coverage-full-tests.log`
- `.greytest-generation-verification/mockito-api-compile.log`
- `.greytest-generation-verification/mockito-coverage-docker-build.log`

Chưa đo phần trăm line/branch coverage JaCoCo hoặc chạy mọi module đầu vào trên tất cả phiên bản Mockito. Script regression dùng Maven stub; probe compiler dùng jar thật. Không gọi LLM thật, không sửa project source/schema hoặc artifact hiện có của người dùng.

Sau khi backend được cập nhật, cần tải ZIP mới để nhận script mới. ZIP mới không xóa file MockMaker còn sót từ lần giải nén ZIP cũ; nếu file đó chứa `mock-maker-subclass` và project dùng Mockito dưới 5, cần loại bỏ resource do GreyTest cũ tạo, đồng thời giữ cấu hình MockMaker vốn có của project. Code test cũ dùng API sai phiên bản cũng cần được sửa/sinh lại; bản sửa không tự ghi đè artifact đã lưu.
