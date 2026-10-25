# Kiểm tra luồng cập nhật và xóa method

Ngày kiểm tra: 19/10/2026. Phạm vi: AST diff → impact → sinh tăng dần BR/Plan/Case/Unit Test → duyệt → Apply; kiểm tra thêm Cancel.

**Kết luận: hai luồng chưa đúng đầy đủ.** Mô tả BR → Plan → Case → Unit Test là luồng mong muốn; implementation hiện tại còn các lỗi dưới đây. Lần kiểm tra này không sửa logic production, không gọi AI thật và không sửa artifact của project người dùng.

## Luồng đang có

| Bước | Method cập nhật | Method bị xóa |
| --- | --- | --- |
| AST diff | `MODIFIED`, nhận diện bằng class + signature | `DELETED`, nhận diện bằng class + signature |
| Impact | Tìm BR của method, Plan qua BR chính và bảng covered-rule, rồi Case và Unit Test | Cùng cơ chế tìm phụ thuộc |
| Đề xuất | Tạo các item `UPDATE` | BR chuyển `REMOVE`; Plan chuyển `REMOVE` nếu BR chính bị xóa, rồi kéo xuống Case/Unit Test |
| Sinh tăng dần | Gọi BR → Plan → Case → Unit Test; source Unit Test đọc từ candidate snapshot | Loại những ID có đề xuất `REMOVE` khỏi các bước sinh |
| Apply | Cập nhật các target theo ID và quảng bá snapshot | Xóa Unit Test → Case → Plan → BR → method rồi quảng bá snapshot |

## Lỗi ưu tiên cao — đã tái hiện

1. **Bước sinh sau đọc artifact cũ.** `IncrementalGenerationService` lưu nội dung BR/Plan/Case mới vào `SourceUpdateItem.afterData`, trong khi `GenerationContextBuilder` đọc bảng artifact hiện hành. Kiểm chứng thấy Plan nhận BR cũ; Case nhận Plan cũ; Unit Test nhận source mới nhưng `expectedResult` cũ. Điều này có thể gây generation error hoặc kết quả không khớp Test Case sẽ được Apply. Điểm code: `IncrementalGenerationService.java:247,277,317,347,379,454`; `GenerationContextBuilder.java:404,425,500,546`.

2. **Từ chối xóa mục con vẫn có thể mất mục con.** Chấp nhận xóa BR nhưng từ chối xóa Plan/Case/Unit Test, Apply vẫn xóa BR. Foreign key `ON DELETE CASCADE` xóa các mục con dù proposal của chúng mang trạng thái `REJECTED`. Kiểm chứng với PostgreSQL thật đã tái hiện. Điểm code: `SourceUpdateService.java:544,554`; migration V7, V8, V9.

3. **Cancel tác động nhầm overload.** Thêm đề xuất `send(String)`, chưa chạy sinh, rồi hủy: `revertMethodUpdate` tìm theo tên `send` và xóa nhầm method baseline `send(int)`. Nhánh UPDATE cũng có thể ghi source của một overload sang các overload khác. Điểm code: `SourceUpdateService.java:1043,1057`.

## Lỗi khác đã tái hiện

4. **Hai Plan cùng BR bị gộp vào một target.** Response có happy path và exception cùng BR tạo hai UPDATE trỏ cùng Plan ID; Apply chỉ giữ nội dung cập nhật cuối. Điểm code: `IncrementalGenerationService.java:284–288`.

5. **Apply không cập nhật đủ thuộc tính của artifact đã tồn tại.** Reused Plan/Case bỏ qua `testType`; reused Case bỏ qua `traceSource`. Kiểm chứng Case có description/expectedResult mới nhưng type và trace vẫn cũ. Điểm code: `SourceUpdateService.java:634–642,682–692`.

6. **Xóa method nhưng class source trong DB còn method đó.** Nhánh đồng bộ source loại mọi item `REMOVE`. Kiểm chứng sau Apply, method/BR/Plan/Case/Unit Test đã mất nhưng `JavaClass.sourceCode` vẫn chứa method đã xóa. Điểm code: `SourceUpdateService.java:825–826`.

## Lỗi thấy qua đối chiếu code, chưa có ca tái hiện riêng

7. **Bỏ nhánh trong method chưa loại BR cũ.** Analyze đánh dấu các BR của method MODIFIED là UPDATE; generation chỉ xử lý rule được trả về, không đối chiếu anchor đã biến mất. Apply có thể giữ BR/Plan/Case cũ và xóa cờ modified dù không có payload thay thế.

8. **Ảnh hưởng từ helper lên caller chưa được dùng đầy đủ.** `ImpactAnalysisService` có thu thập caller vào `affectedServiceMethods`, nhưng vòng tìm BR chỉ duyệt `changedMethods`; việc sinh BR cũng chọn method trực tiếp thay đổi. Caller không đổi source có thể bị bỏ sót khi helper thay đổi. Điểm code: `ImpactAnalysisService.java:95,104`; `IncrementalGenerationService.java:511`.

Ngoài ra, matching Case theo thứ tự response (`oldCases.get(i)`/`newCases.get(i)`) chưa bảo đảm giữ đúng danh tính scenario khi AI đổi thứ tự. Chưa quy kết đây là ca lỗi đã chạy.

## Kiểm chứng

- Chạy lại **34 test liên quan: 34 pass, 0 fail, 0 error, 0 skip**. Các test có sẵn chưa bao phủ các lỗi mới tái hiện.
- Chương trình chẩn đoán riêng: `.greytest-generation-verification/flow-audit/FlowAudit.java` ở workspace gốc. Dùng Spring context, repository, context builder, Apply và Cancel thật; chỉ mock ranh giới `AIAgentService`.
- Chạy 5 kịch bản: cập nhật đủ 4 bước; xóa chấp nhận toàn bộ; xóa có mục con bị từ chối; hủy thêm overload; nhiều Plan chung BR. Ghi được **12 quan sát có assertion xác nhận**.
- Mỗi kịch bản tạo project riêng và luôn rollback transaction, kể cả khi assertion lỗi. Không sử dụng ID project 248 hoặc artifact hiện có. Không gọi LLM, không tiêu quota AI. Snapshot kiểm chứng nằm riêng trong thư mục chẩn đoán.
- Reviewer độc lập đã đối chiếu code và chẩn đoán. Lưu ý: ví dụ Unit Test có assertion cũ dùng response AI giả, nên chứng minh sự bất nhất trong pipeline; không chứng minh LLM thật đã sinh và vượt semantic validation với assertion đó. Bằng chứng trực tiếp là context có source mới và expected result cũ.
- Log: `.greytest-generation-verification/flow-audit/run.log`, `observations.txt`, `regression-tests.log`.

## Hai hướng xử lý

**Phương án 1 — khuyến nghị:** dựng context từng bước từ artifact hiện hành cộng các proposal mới nhất của bản cập nhật, giữ nguyên ID và chưa ghi đè artifact cũ trước Apply. Đồng thời xác thực quan hệ cha/con trước xóa, rollback theo đầy đủ signature, đồng bộ class source cho cả REMOVE, và cập nhật đủ trường đã duyệt. Tiếp theo bổ sung đối chiếu scenario/branch để xử lý các nhánh không còn tồn tại. Hướng này giữ trải nghiệm “Sinh Tất Cả → duyệt → Apply” và phù hợp yêu cầu giữ bản ghi hiện có.

**Phương án 2:** yêu cầu duyệt và Apply từng tầng BR → Plan → Case → Unit Test để bước sau đọc dữ liệu vừa lưu. Ít nhu cầu dựng context proposal, nhưng thay đổi quy trình sử dụng, không còn một lần Apply toàn bộ, và cần xử lý trạng thái dở dang giữa các tầng. Các lỗi xóa/cancel vẫn phải sửa riêng.

Chưa thực hiện hai phương án trong lần audit này vì cần thống nhất cách xử lý artifact được giữ lại khi cha bị xóa và phạm vi thay đổi quy trình. Theo AGENTS.md, thay đổi lớn hoặc logic nghiệp vụ cần xác nhận trước.
