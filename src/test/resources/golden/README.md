# Golden manifest Spring PetClinic

`petclinic-analysis.json` khóa source ở commit
`b3ee2c53e76e9267f03551a7cd36b0983c859c56` của
`spring-projects/spring-petclinic`.

Manifest **1.1** giữ 25 lớp và **62 phương thức** trong phạm vi production.
Danh sách endpoint và relation được giữ nguyên từ manifest 1.0.

## Chính sách phương thức

`JavaParserHelper.extractMethods` bỏ getter/setter bean đơn giản: không có
annotation hoặc throws, thân chỉ trả trực tiếp một field hoặc gán tham số
vào field. Phương thức có xử lý nghiệp vụ, endpoint và accessor có annotation
vẫn theo chính sách hiện tại của parser.

Manifest 1.0 có 86 phương thức, bao gồm 24 accessor đơn giản không thuộc
phạm vi này. Sau khi đối chiếu từng thân phương thức trên snapshot đã khóa,
manifest 1.1 bỏ đúng 24 accessor đó. Không lấy danh sách actual của parser
để tự động thay toàn bộ expected; tên lớp, phương thức còn lại và endpoint
vẫn được đối chiếu chính xác trong golden test.

Bản trước/sau và 24 thân phương thức được lưu tại
`outputs/chapter-5/skipped-tests-20261020/golden` ở workspace. Kết quả Fail
62/86 của lượt cũ vẫn là bằng chứng lịch sử; không sửa lại số liệu cũ.

## Chạy test

Từ thư mục `backend`, truyền root của đúng snapshot đã khóa:

```powershell
mvn -o '-Dtest=PetClinicGoldenManifestTest,JavaParserHelperTest' '-Dpetclinic.path=D:/.KLTN/outputs/chapter-5/corpus-analysis-20261010/golden-source' test
```

Không cần PostgreSQL hoặc provider AI. Bỏ `petclinic.path` khiến golden test
Skip theo JUnit assumption; việc đó không xác nhận kết quả golden đạt.
