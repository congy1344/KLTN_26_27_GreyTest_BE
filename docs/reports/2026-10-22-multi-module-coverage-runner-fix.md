# Sửa source checker nhiều module và Maven PATH trên Windows

## Vấn đề

UT đã chạy được nhưng ZIP coverage có hai lỗi hạ tầng:

1. `GreyTestSourceCheck.java` chỉ cho phép bỏ một module prefix chung cho toàn bộ
   manifest. Context của `appointment-service` có thêm source từ `common-lib`, nên
   chạy ở module root bị báo thiếu source dù các module vẫn ở đúng cây thư mục.
2. Runner kiểm tra Maven bằng `call mvn -v` rồi lưu tên `mvn`, nhưng chạy build bằng
   `call "mvn"`. Đã tái hiện việc `%~dp0`/`%~f0` bên trong batch Maven trỏ về thư mục
   hiện tại với cách gọi tên ngắn có quote, làm sai vị trí thư mục bootstrap Maven.
   Không phải mọi môi trường đều biểu hiện như một lỗi không tìm thấy lệnh.

Xóa dòng dependency trong manifest chỉ giúp bỏ qua bước kiểm tra source đó. Bản sửa
giữ nguyên toàn bộ metadata fingerprint và đối chiếu cả source module phụ thuộc.

## Thay đổi

- Helper Java 8 phân giải toàn bộ manifest theo các prefix module đã ghi nhận và
  cấu trúc thư mục cha của module hiện tại. Không quét filesystem theo basename.
- Các file phải thuộc một layout đầy đủ, nhất quán. Nhiều layout đầy đủ khác nhau
  vẫn bị từ chối do ambiguity; không chọn layout dựa trên hash khớp.
- Khi có `src/main/java` đang hoạt động, layout phải chứa source ở đó. Không được
  xác nhận nhầm bản copy cũ khi layout đang chạy thiếu dependency.
- Giữ kiểm tra SHA-256, giới hạn metadata/file, chống traversal và kiểm tra đường dẫn
  thật. Junction/symlink của module phụ thuộc không được trỏ ra ngoài project đã ghi nhận.
- Runner tìm các Maven launcher trong PATH bằng `where mvn.cmd mvn.bat mvn.exe`,
  kiểm tra từng launcher và lưu đường dẫn tuyệt đối của launcher chạy được.
- README xuất trong ZIP giải thích phải giữ cây module và đủ manifest.

Các file chính:

- `src/main/resources/coverage/GreyTestSourceCheck.java`
- `src/main/java/com/greytest/service/UnitTestFileService.java`
- `src/test/java/com/greytest/service/GenerationSourceArchiveTest.java`
- `src/test/java/com/greytest/service/CoverageToolBootstrapTest.java`

Không hard-code tên `appointment-service`, `common-lib`, class hay đường dẫn trên máy
người dùng trong logic sửa lỗi. Không đổi source nghiệp vụ hay bỏ dòng manifest.

## Kiểm chứng

Đã chạy red trước sửa: 15 tests, 5 failures, bao gồm Maven tự xác định thư mục cài đặt
và source khác module. Sau review, test source hiện tại bị sửa + dependency thiếu +
bản copy cũ đầy đủ cũng thất bại trước khi thêm guard cho module hiện tại.

Lệnh cuối chạy tại backend:

```powershell
.\mvnw.cmd -o '-Dtest=GenerationSourceArchiveTest,CoverageToolBootstrapTest,UnitTestFileServiceTest,UnitTestFixtureExportTest,GenerationSourceFingerprintTest,GenerationPerformanceTest,MultiSourceGenerationRegressionTest' test
```

Kết quả: **98 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**. Trong đó có
16 lượt source/archive và 14 lượt bootstrap tool. Helper được compile với target
Java 8; các script được thực thi trên Windows CMD và Git Bash.

Đã xuất ZIP thật bằng `UnitTestFileService.createCoverageArchive`, dùng 8 test
case 2546–2553 từ log hospital và fingerprint của đủ 10 file. Chạy
`run-greytest-coverage.cmd` trên bản sao có đường dẫn Unicode:

```text
.greytest-runtime/multi-module-runner/source mẫu/
  hospital-management-demo-main/backend/
    appointment-service/
    common-lib/
```

Kết quả thực tế:

```text
[GreyTest] Source version verified for 10 generation files.
# Tạo junction ASCII, sau đó kiểm tra lại thành công đủ 10 file.
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
Analyzed bundle 'appointment-service' with 15 classes
BUILD SUCCESS
[GreyTest] SUCCESS: target\site\jacoco\jacoco.xml
```

Đã xác nhận manifest vẫn có 10 dòng và đường dẫn `common-lib/.../ResourceNotFoundException.java`.
XML tạo mới parse được, report `appointment-service`, 9 package, 82.648 byte, runner
exit 0. Dùng Maven thật từ PATH, không dùng fallback trong lượt smoke này.

Bằng chứng tại workspace root:

- `.greytest-generation-verification/multi-module-runner/red.log`
- `.greytest-generation-verification/multi-module-runner/active-layout-red.log`
- `.greytest-generation-verification/multi-module-runner/targeted-final.log`
- `.greytest-generation-verification/multi-module-runner/hospital-coverage.log`
- `.greytest-generation-verification/multi-module-runner/hospital-coverage.exit.txt`
- `.greytest-generation-verification/multi-module-runner/StageHospitalRunner.java`

Reviewer chỉ đọc đã kiểm tra bản cuối; finding về snapshot cũ đã sửa và có regression.

## Áp dụng và giới hạn

Rebuild backend, tải lại ZIP để nhận helper/runner mới, giải nén tại module cần chạy.
Giữ các module phụ thuộc trong cùng cây project và giữ đủ manifest; không cần gọi
AI sinh lại các UT đã đúng chỉ để áp dụng bản sửa runner này.

Chưa rebuild container đang chạy. Lượt smoke tái hiện dùng 8 test từ log hiện có,
không phải bản ZIP 41 test trên máy người dùng. Maven vẫn cần các dependency nội bộ
được build/install đúng theo cấu hình project; checker không tự build module phụ thuộc.
