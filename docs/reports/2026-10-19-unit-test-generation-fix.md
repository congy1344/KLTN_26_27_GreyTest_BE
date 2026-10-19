# Kiểm chứng bản sửa sinh Unit Test — 19/10/2026

## Phạm vi và nguyên nhân

Sửa bộ sinh test của **GreyTest**. Không sửa production hoặc test của các dự án đầu vào.

Log `log/20261016-055004-058-33-unit-test.log` có `Recipient` sử dụng
`Map<NotificationType, NotificationSettings>` nhưng không có khai báo `NotificationSettings`.
Log response `20261016-055137-909-35-unit-test-response-2.log` sau đó chứa
`Map<NotificationType, Object>`, `SettingsFixture` giả và helper ném `UnsupportedOperationException`
cho các trường hợp TC-989, TC-990, TC-994. Đây là bằng chứng lỗi sinh fixture; chưa phải
kết quả chạy test của notification-service.

Bản sửa:

- Gom dependency nhiều tầng qua DTO/entity, generic, interface và enum; không đưa toàn bộ lớp cùng package vào context.
- Ưu tiên kiểu fully qualified, explicit import, cùng package rồi wildcard import. Khi dùng dữ liệu cũ thiếu import, chỉ suy ra tên trong phạm vi ứng dụng nếu không mơ hồ.
- Báo lỗi khi kiểu không xác định được hoặc cần hơn 25 supporting classes, thay vì âm thầm bỏ khai báo.
- Giữ nguyên class source gửi AI, bao gồm phần sau 4.000 ký tự. Phân tích mới giữ package/imports và loại top-level type không liên quan; nested type giữ lớp bao ngoài.
- Chặn helper local luôn ném `UnsupportedOperationException` khi được gọi trực tiếp từ test; chuyển phản hồi lỗi qua vòng semantic retry có sẵn. Không chặn nhầm dependency stub hoặc helper overload chưa xác định được.
- Bổ sung prompt yêu cầu dùng đúng production type/generic và cấm fixture giả/placeholder.
- Giữ Test Case đã duyệt làm mục tiêu: không đổi scenario hoặc expected result khi retry. Thiếu source hoặc hành vi không được implementation hỗ trợ phải trả `generation_error` với `case_id`, lý do và bằng chứng, thay vì sinh test cho đủ số lượng.
- Parser nhận riêng `MISSING_SOURCE`/`UNSUPPORTED_BEHAVIOR`, từ chối trộn lỗi với `unit_tests` và dừng không retry cùng dữ kiện. Gemini giữ JSON mode nhưng không ép schema chỉ cho phép `unit_tests`, để có thể báo thiếu source.
- Prompt quy định package/class phải khớp source và đường dẫn module-relative `src/test/java/<package>/<TestClass>.java`. Luồng hiện có kiểm tra package/class trước khi lưu, tạo đường dẫn từ metadata hợp lệ và đối chiếu import dự án với khai báo trong context.

## A. Test files

Các file dưới đây thuộc `src/test/java/com/greytest/`; không sinh test cho notification-service trong lần sửa này.

| File | Production class | Test thêm | Tổng test trong lớp |
| --- | --- | ---: | ---: |
| `service/agent/GenerationContextBuilderTest.java` | `GenerationContextBuilder` | 6 | 25 |
| `service/agent/GeneratedUnitTestSemanticValidatorTest.java` | `GeneratedUnitTestSemanticValidator` | 3 | 114 |
| `service/analysis/AnalysisServiceTest.java` | `AnalysisService` | 1 | 3 |
| `service/agent/AgentGatewayTest.java` | `GenerationResponseParser`, `AIAgentService` | 3 | 31 |
| `service/agent/GoogleLlmClientTest.java` | `GoogleLlmClient` | 1 | 18 |

Điều chỉnh assertion của test sẵn có `unitTestContextKeepsCompleteTargetSourcePastFourThousandCharacters`
để kiểm tra source nguyên vẹn trong JSON gửi AI. Không xóa test hay bỏ assertion để làm pass.
Cập nhật các assertion hợp đồng prompt/schema khi cho phép báo lỗi thiếu source và thay "compile pass" giả định bằng kiểm tra source. Tổng cộng thêm 14 test method.

## B. Test inventory

Các test hồi quy của GreyTest được định danh bằng tên method; không gán mã TC nghiệp vụ mới.

| Test method | Method được kiểm tra | Kịch bản | Kết quả |
| --- | --- | --- | --- |
| `unitTestContextIncludesTransitiveGenericTypesWithoutUnrelatedClasses` | `buildUnitTestContext` | Dependency nhiều tầng, generic, enum, chu trình tham chiếu; loại lớp không liên quan | PASS |
| `unitTestContextUsesWildcardImportsBeforeApplicationPackageFallback` | `buildUnitTestContext` | Wildcard import thắng fallback cùng ứng dụng | PASS |
| `unitTestContextPrefersSamePackageOverWildcardImportedLookalikes` | `buildUnitTestContext` | Cùng package thắng wildcard import | PASS |
| `unitTestContextIgnoresNameCollisionsOutsideTheApplicationPackage` | `buildUnitTestContext` | Tên trùng ngoài namespace ứng dụng | PASS |
| `unitTestContextRespectsExplicitImportsOverSamePackageLookalikes` | `buildUnitTestContext` | Explicit import thắng lớp cùng tên; không nhầm JDK Date với model Date | PASS |
| `unitTestContextDoesNotSilentlyDropDependenciesAtTheClassLimit` | `buildUnitTestContext` | Hơn 25 dependency phải báo thiếu context | PASS |
| `rejectsFixtureHelpersThatUnconditionallyThrowUnsupportedOperationException` | `validate` | Không nhận helper fixture placeholder | PASS |
| `allowsUnsupportedOperationExceptionFromAProductionDependencyStub` | `validate` | Dependency stub ném exception vẫn hợp lệ | PASS |
| `allowsValidFixtureWhenAnUnselectedOverloadThrowsUnsupportedOperationException` | `validate` | Không chặn helper overload hợp lệ | PASS |
| `preservesPackageAndImportsForUnitTestGeneration` | `analyze` | Giữ package/imports, tách top-level type khác trong cùng file | PASS |
| `unitTestContextKeepsCompleteTargetSourcePastFourThousandCharacters` | `buildUnitTestContext` | Source nguyên vẹn trong prompt; semantic validator dùng source thật | PASS |
| `stopsWithoutRetryWhenSourceCannotSupportAUnitTest` | `generateUnitTests`, `parse` | Báo đúng case/lý do và chỉ gọi LLM một lần cho cả thiếu source và hành vi không được hỗ trợ | PASS |
| `rejectsIncompleteUnitTestGenerationErrors` | `parse` | Chặn mã case sai kiểu, thiếu chi tiết và lý do ngoài hợp đồng | PASS |
| `rejectsUnitTestsMixedWithADeclaredGenerationError` | `parse` | Không nhận test khi phản hồi đồng thời khai báo lỗi generation | PASS |
| `unitTestPromptAllowsSourceEvidenceErrorsWithoutForcingUnitTests` | `requestBody` | Request Gemini giữ JSON mode, không có schema bắt buộc phải sinh test | PASS |

Các lỗi hồi quy đã được chạy trước bản sửa tương ứng và quan sát thất bại.

## C. Coverage thực đo

JaCoCo **0.8.12**, đo từ lần build sạch toàn backend cuối: **585 test được thống kê, 584 pass và 1 test có sẵn skip**.
Counter METHOD của JaCoCo bao gồm các method lambda do compiler sinh.

| Class | Method covered/total | Method coverage | Branch covered/total | Branch coverage |
| --- | ---: | ---: | ---: | ---: |
| `GenerationContextBuilder` | 136/142 | 95,77% | 219/322 | 68,01% |
| `GeneratedUnitTestSemanticValidator` | 359/379 | 94,72% | 1100/1663 | 66,15% |
| `AnalysisService` | 25/32 | 78,12% | 67/106 | 63,21% |
| `GenerationResponseParser` | 8/9 | 88,89% | 69/94 | 73,40% |
| `AIAgentService` | 25/40 | 62,50% | 64/106 | 60,38% |
| `GoogleLlmClient` | 31/31 | 100,00% | 78/108 | 72,22% |

Method chưa được thực thi trong lần đo này gồm `GenerationContextBuilder.buildTestCaseContext`,
`GeneratedUnitTestSemanticValidator.isArgumentCaptorScope`, `hasTrailingJunit5Message`,
`AnalysisService.getAnalysisResult`, `countExistingTestFilesIfSourceExists`, cùng một số lambda.
Nhánh chưa được thực thi trong phần mới gồm một số đường chọn kiểu của `isUnitTestDependency`,
đường kiểm tra scope trong `sourceTypeReferences`, các đường exception/lambda/try trong
`validatePlaceholderHelpers`, và nhánh nested type trong `classSource`.
`AIAgentService` còn các overload sinh BR/TP/TC/UT và coverage-refinement chưa được gọi trực tiếp;
parser còn nhánh schema/error payload chưa cover hết. Method coverage 100% của `GoogleLlmClient`
không đồng nghĩa branch coverage 100%, cũng không chứng minh API provider thật đã được gọi.

Danh sách đầy đủ theo method và dòng/nhánh:

- XML: `target/site/jacoco/jacoco.xml`.
- HTML: `target/site/jacoco/index.html`.
- Class HTML: `target/site/jacoco/com.greytest.service.agent/GenerationContextBuilder.html`,
  `target/site/jacoco/com.greytest.service.agent/GeneratedUnitTestSemanticValidator.html`,
  `target/site/jacoco/com.greytest.service.analysis/AnalysisService.html`.

Không sửa `pom.xml` hay production của dự án đầu vào để tăng coverage.

## D. Build verification

Maven 3.9.14, JDK 17.0.18, chạy tại backend. Maven executable thực tế:
`C:/Users/KhaiTien/.m2/wrapper/dists/apache-maven-3.9.14/ed7edd442f634ac1c1ef5ba2b61b6d690b5221091f1a8e1123f5fadcc967520d/bin/mvn.cmd`.

Lệnh build sạch toàn module kèm JaCoCo, với Byte Buddy agent có sẵn để tránh lỗi self-attach:

```powershell
mvn -o clean org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent '-Djacoco.propertyName=greytestJacocoAgent' '-DargLine=@{greytestJacocoAgent} -javaagent:C:/Users/KhaiTien/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar' test org.jacoco:jacoco-maven-plugin:0.8.12:report
```

Kết quả: **BUILD SUCCESS**, compile lại 227 production source và 67 test source;
585 test được thống kê, **584 pass, 0 failure, 0 error, 1 skipped**.
Test có sẵn `PetClinicGoldenManifestTest.matchesFrozenPetClinicManifest` bị skip do
chưa đặt `-Dpetclinic.path=<checkout>`. Không thêm cơ chế skip mới.
Log cuối: `../.greytest-generation-verification/source-grounding-final-tests.log`.

Lệnh test/coverage riêng ở mốc sửa context trước đó (142 test, không phải lần xác minh cuối):

```powershell
mvn -o org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent '-Djacoco.propertyName=greytestJacocoAgent' '-DargLine=@{greytestJacocoAgent} -javaagent:C:/Users/KhaiTien/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar' '-Dtest=GenerationContextBuilderTest,GeneratedUnitTestSemanticValidatorTest,AnalysisServiceTest' test org.jacoco:jacoco-maven-plugin:0.8.12:report
```

Kết quả: **142 pass, 0 failure/error/skipped**, **BUILD SUCCESS**, sinh XML/HTML JaCoCo.
Log: `../.greytest-generation-verification/coverage-tests.log`.

`git diff --check` không báo lỗi. Reviewer chỉ đọc đã kiểm tra lại sau khi sửa các finding
về tên kiểu trùng, overload helper, top-level type lẫn nhau và schema Gemini ngăn báo thiếu source;
không còn finding cần xử lý. Parser error, schema Gemini và retry guidance đều đã được review lại.

## E. Giới hạn và việc còn lại

- Chưa gọi provider LLM thật và chưa chạy lại notification-service: workspace không có source module đó.
  Vì vậy TC-989/TC-990/TC-994 trong log cũ là **NOT RUN**, không được coi là test đã pass.
- Kiểm tra AST là quality gate trước khi lưu output, không thay thế Maven compile/test của dự án đầu vào.
- Artifact test đã lưu trước bản sửa không được tự động chỉnh sửa. Dữ liệu phân tích cũ vẫn có thể thiếu import;
  nếu tên kiểu mơ hồ, generator sẽ báo lỗi. Không tự chạy phân tích lại/reset pipeline của người dùng.
- Xác minh toàn bộ trong sandbox bị lỗi Byte Buddy attach/compiler resources và class build dở.
  Chạy build sạch ngoài sandbox với agent nạp sẵn đã xác minh thành công; không sửa business logic để né lỗi môi trường.
- Không commit, thay schema hay mở rộng chức năng chạy source người dùng trên server.

Trạng thái bản sửa GreyTest: **đã kiểm chứng bằng test và reviewer**.
Trạng thái quality gate end-to-end cho notification-service: **PARTIAL / NOT RUN**, cần source và lần sinh/chạy test thực tế.
