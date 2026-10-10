# Kiểm chứng môi trường biên dịch corpus — 10/10/2026

Đây là **build preflight**, không phải kết quả sinh Unit Test bằng AI, thực nghiệm 75 lượt hoặc UAT. Bốn corpus đã đạt `COMPILE_PASS`; Realworld bị chặn ở bước tải đúng Gradle 7.4. Cả năm vẫn `ready_for_paid_runs=false`, oracle vẫn `HUMAN_REVIEW_PENDING`.

## Môi trường và nguồn

- Windows 11, Maven 3.9.9, Temurin JDK **17.0.19+10**. JDK chạy build khác với Java target bên dưới.
- Bản sao riêng: `outputs/chapter-5/build-preflight-20261010/corpus/<dataset>`. Materialize public Java, POM/Gradle, resource, OpenAPI, wrapper và license trực tiếp từ Git objects tại đúng commit trong `scripts/evaluation/datasets.json`; không dùng HEAD thay frozen commit.
- Không sao `.git`, `.env`, private key/keystore hoặc credential files. Java domain classes có tên Credentials/Secrets là public source và được giữ nguyên. Không sửa source/POM/build.gradle upstream, nguồn dùng AST hoặc nguồn dùng baseline prompt.
- `*-inventory.json` lưu Git blob SHA1, SHA256, kích thước từng file. Mỗi blob được kiểm SHA1 lúc materialize; toàn inventory được kiểm SHA256 lại sau build. Production Java counts khớp corpus manifest: 72/83/93/519/1167.
- Raw Git blob dùng LF nên scope SHA256 khác checkout trước có CRLF. `summary.json` ghi hai hash và kiểm tương đương sau CRLF → LF; không cập nhật hash/snapshot gốc.

## Kết quả thực tế

| Dataset | Lệnh biên dịch cuối | Java target hiệu lực | Thời gian | Kết quả |
|---|---|---|---:|---|
| PiggyMetrics | `mvn -o -B -ntp -pl account-service -am test-compile` | source/target 1.8; selected class major 52 | 30.145 s | COMPILE_PASS |
| PetClinic REST 3.4.0 | `mvn -B -ntp test-compile` | release 17; selected class major 61 | 52.187 s | COMPILE_PASS |
| Realworld | dự kiến `gradlew.bat --no-daemon compileJava compileTestJava` | build.gradle khai báo source/target 11; chưa xác minh thực thi | N/A | BLOCKED_DISTRIBUTION_DOWNLOAD |
| mall | `mvn -o -B -ntp -pl mall-admin -am test-compile` | release/source/target 17; selected class major 61 | 86.135 s | COMPILE_PASS |
| Shopizer | `mvn -B -ntp -pl sm-core -am test-compile` | source/target 11; selected class major 55 | 60.469 s | COMPILE_PASS |

Thời gian là wall clock của lệnh trên máy hiện tại, bao gồm compile và tải dependency khi cần, **không phải thời gian phản hồi GreyTest hoặc latency AI**. Không chạy phase `test`, không khởi động MongoDB/Redis/MySQL/PostgreSQL, server hoặc frontend. PetClinic chạy OpenAPI generator và MapStruct theo POM thật trước khi compile; 18 test source được biên dịch. mall reactor gồm mall-common, mall-mbg, mall-security, mall-admin. Shopizer reactor gồm sm-core-model, sm-core-modules, sm-core: scope ShoppingCartServiceImpl nằm trong sm-core nên thay draft sm-shop bằng đúng module này; phạm vi production để đánh giá không đổi.

`*-effective-pom.xml` được xuất với Maven help plugin 3.5.1 bằng CLI, không thêm dependency vào corpus. Offline help mặc định và explicit plugin ban đầu thiếu cache; lần online thành công cho cả bốn. `summary.json` lưu compiler properties và dependency coordinates đã resolve từ effective POM, hash log build cùng hash selected class đã compile. JDK và Maven version có artifact riêng.

## Lỗi và retry được giữ lại

- PetClinic offline thiếu cached spring-boot-maven-plugin 3.4.0; retry online thành công, không bỏ code generation hoặc chỉnh dependency.
- Shopizer offline ban đầu lỗi thiếu CredentialsReset do bộ lọc filename của bước materialize quá rộng. Đã phục hồi bảy Java classes public nguyên văn từ frozen Git blobs, cập nhật inventory và kiểm toàn bộ hash trước kết luận. Đây là lỗi chuẩn bị bản sao, không phải lỗi upstream hoặc lỗi GreyTest. Log thất bại vẫn lưu; final online reactor thành công.
- Realworld wrapper launcher đã được đọc kiểm tra; wrapper JAR/properties từ exact commit, distribution URL `https://services.gradle.org/distributions/gradle-7.4-bin.zip`. Gradle 7.4 không có trong cache; các version 8/9 đang có không được dùng thay. Thử wrapper offline thất bại vì sandbox không cho tạo lock dưới global Gradle cache, chưa vào compile task.
- Tải distribution và official SHA256 qua services.gradle.org thất bại TLS `WinError 10054`. Một fallback sang official downloads.gradle.org cũng bị reset. Hai verification artifacts cuối đều `verified=false`; không có distribution được xác minh hoặc thực thi. Không sửa wrapper version hoặc khai báo checksum thành công khi tải thất bại.

Hai hướng xử lý Realworld: (1) tải đúng Gradle 7.4 cùng SHA256 từ nguồn chính thức vào workspace khi kết nối hoạt động, rồi chạy đúng compile tasks với Gradle home riêng; (2) chạy cùng frozen commit/JDK target trên máy của sinh viên đã có đúng Gradle 7.4 và lưu log/exit/hash tương đương. Đổi version hoặc corpus là thay đổi protocol, cần thảo luận trước.

## Gate còn lại

`test-compile` chỉ chứng minh production và existing test source thuộc reactor được biên dịch, không chứng minh test đã chạy, assertion đúng, generated JUnit5 compile hoặc coverage đạt. PiggyMetrics dùng JUnit4 ở toàn bộ 25 test files; Shopizer có 24 file import JUnit4 và một file import JUnit5. Cần adapter JUnit5/Mockito được đóng băng và pilot riêng trước khi dùng generated tests; không sửa upstream dependencies trong preflight này. PetClinic/Realworld/mall có JUnit5 source, nhưng việc có framework không chứng minh output AI đúng.

Tiếp tục sau build: human signoff scope/BR/case thủ công, dependency source whitelist, adapter và pilot compile/run/JaCoCo từng dataset, sau đó mới mở gate các phương pháp/lần lặp. Không gọi provider hoặc ký thay hai người UAT trong bước này.

Artifact chính: `outputs/chapter-5/build-preflight-20261010/summary.json`, full `*-offline.log`/`*-online.log`, run JSON (command/exit/duration), inventories, effective POM/logs, `jdk.txt`, `maven.txt`, Gradle verification JSON. Không thêm script sản phẩm, không commit/push.
