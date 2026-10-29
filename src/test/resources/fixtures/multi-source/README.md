# Multi-source regression snapshots

Các file `.java.txt` và build file `.txt` được sao chép nguyên văn từ source mẫu trên máy ngày 2026-10-20.
Chúng chỉ là dữ liệu đầu vào của test JavaParser/metadata/export, không được compile thành production class
hoặc đưa vào source mẫu để làm test pass. Regression này không gọi LLM hoặc phụ thuộc đường dẫn tuyệt đối.

| Dataset | Origin relative to sample project | Purpose |
| --- | --- | --- |
| realworld | `src/main/java/io/spring/application/data/ProfileData.java`, `build.gradle` | Lombok 0/5 arguments; Java 11, JUnit 5 |
| hms | `src/main/java/com/example/HMS/dto/DepartmentDto.java`, `service/AppointmentService.java`, `enums/AppointmentStatus.java`, `build.gradle` | Exact constructor; reject stale method/enum/FQN; Java 17, JUnit 5 |
| piggymetrics | `auth-service/src/main/java/com/piggymetrics/auth/domain/User.java`, root `pom.xml` | Implicit no-arg constructor; Java 8, JUnit 4, Mockito 2 |
| hospital-demo | `backend/patient-service/src/main/java/com/hospital/patient/dto/PatientResponseDto.java`, module `pom.xml` | Explicit plus Lombok constructors; Java 17, JUnit 5 |
| eshop | `apps/payment-service/src/main/java/eshop/paymentservice/rabbitmq/events/PaymentResponse.java` | UUID/boolean all-args constructor; preserve exact import |
| ecommerce | `order-service/src/main/java/com/selimhorri/app/dto/response/collection/DtoCollectionResponse.java` | Generic DTO with Builder; do not infer unsupported constructor rules |

Các đường dẫn `service/...` và `enums/...` ở dòng HMS cùng thuộc `src/main/java/com/example/HMS/`.
Snapshot được giữ nguyên package/import/annotation/field/method của source gốc.

Runtime matrix của 6 source / 12 module được lưu riêng trong báo cáo
`docs/reports/2026-10-20-multi-source-coverage.md`. Kết quả regression không thay thế compile/test thực tế.
