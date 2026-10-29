# Kiểm tra context và đầu ra Unit Test — project 734

Phạm vi: đối chiếu báo cáo ba lỗi và các diff sửa `src/test/` với source được GreyTest lưu, context thực sự gửi LLM từ BR đến UT, Test Case đã duyệt và Unit Test trong database. Không sửa source mẫu, artifact nghiệp vụ hoặc logic bộ sinh trong lần kiểm tra này.

## Kết luận

Các khai báo Java cần thiết để tránh lỗi getter và assertion phân trang đã có trong context. Hai lỗi vẫn xảy ra vì bước sinh Java và validator chưa kiểm chứng đầy đủ đầu ra. Riêng lỗi kỳ vọng NPE có khác biệt giữa source được mô tả trong báo cáo và source mà GreyTest đã dùng: không thể gộp thành cùng một nguyên nhân thiếu context.

Hệ thống cũng chưa cung cấp đầy đủ mọi resource có liên kết: có SQL fragment xuyên namespace chưa được nạp, và resource XML chỉ đi vào context BR. Chưa có bằng chứng phần thiếu SQL này gây ra ba lỗi được báo.

## 1. Dữ liệu thực sự đã gửi

Project 734 có 93 production file, parse thành công 93, không có file parse lỗi trong các context đối chiếu.

Đã đối chiếu 27 log context có target ArticleQueryService/CommentQueryService. Tất cả đều có khai báo `ProfileData` với `boolean following`.

| Bước | Java liên quan | Mapper XML |
|---|---|---|
| BR ArticleQueryService | 18 class, có ArticleData, ProfileData, cursor/page, các read service và helper | 3 mapper: ArticleReadService, ArticleFavoritesReadService, UserRelationshipQueryService |
| BR CommentQueryService | 14 class, có CommentData, ProfileData, cursor/page và các read service | CommentReadService và UserRelationshipQueryService; cảnh báo thiếu fragment bên ArticleReadService |
| Test Plan | Có service, DTO và helper liên quan theo từng batch | Không có supportingResources trong schema context |
| Test Case | Có service, DTO và helper liên quan theo từng batch | Không có supportingResources trong schema context |
| Unit Test | Có đầy đủ khai báo Java liên quan hai lỗi đầu; batch Article điển hình 17 class, Comment 13–16 class | Không có supportingResources trong schema context |

Log tiêu biểu trong thư mục `log` ở workspace:

- `20261021-082157-255-62-business-rule.log`: Article BR, 18 class và 3 mapper.
- `20261021-082353-939-70-business-rule.log`: Comment BR, 14 class và cảnh báo `io.spring.infrastructure.mybatis.readservice.ArticleReadService.profileColumns`.
- `20261021-084734-804-2-test-plan.log`: Plan có source Article và ProfileData.
- `20261021-085145-720-7-test-case.log`: Case phân trang có source Article và ProfileData.
- `20261021-090415-720-24-unit-test.log`: UT cho TC-1080, đủ fixture, mock, expected result và source phân trang.
- `20261021-091636-434-41-unit-test.log`: UT Comment có source ProfileData.

`GenerationContextBuilder` dùng closure khai báo Java và giữ full source cho các class này. `BusinessRuleResourceCollector` chỉ gắn resource vào BR. Với `<include>` khác namespace, collector hiện phát cảnh báo thay vì nạp đệ quy fragment được tham chiếu. Vì vậy không thể khẳng định mọi file map xuyên suốt đã được đọc đủ.

## 2. Đối chiếu ba lỗi

### Getter `getFollowing()` không tồn tại

Unit Test đã lưu có lỗi này ở case 2360, 2362, 2364 và 2366, tương ứng TC-1128, TC-1130, TC-1132, TC-1134. Case cuối gọi sai hai lần, khớp năm lỗi compile trong báo cáo.

Source ProfileData trong context có `@Data` và `private boolean following`. Compile bản khai báo này bằng Lombok cục bộ xác nhận:

- Probe gọi `getFollowing()`: javac thất bại, `cannot find symbol`.
- Probe gọi `isFollowing()`: javac thành công.

Diff sửa getter của bạn phù hợp với khai báo này. Nguyên nhân nằm ở bộ sinh và validator, không phải thiếu ProfileData.

Trong `GeneratedTestApiValidator`, `validateCall` dùng bộ suy kiểu receiver đơn giản, chưa giải quyết toàn bộ chuỗi `result.get(...).getProfileData()`. Ngoài ra, khi không tìm thấy đúng một method tương ứng, nó bỏ qua thay vì báo method không tồn tại. Nhánh suy getter Lombok hiện hỗ trợ kiểm tra kiểu/null, chưa thành kiểm tra toàn bộ API getter. Cần xử lý theo khai báo và annotation của từng source, không thay tên getter bằng regex chung.

### Assertion ngược trong phân trang PREV

Unit Test ID 2865, case 2312 / TC-1080:

- TC đã duyệt ghi chính xác thứ tự `[a2, a1]`.
- Mock following trả về chỉ `author1`.
- Expected result của TC ghi `author1=true`, `author2=false`.
- UT đã lưu lại assert index 0 là true, index 1 là false, trái với chính TC và mock.

Replay trace Java xác nhận kết quả phải là `[false, true]`. Diff đảo hai assertion của bạn phù hợp. Lỗi phát sinh ở bước TC → Java UT; không có bằng chứng BR/TC sai hoặc thiếu file trong trường hợp này. Chỉ biên dịch cũng không bắt được lỗi này; cần kiểm chứng fixture/mock/assertion và chạy test.

### Kỳ vọng NPE khi thiếu favorite count

Unit Test ID 2879, case 2310 / TC-1078 đã duyệt mô tả rõ việc unbox null từ Map vào int và kỳ vọng NPE.

Source ArticleQueryService trong Docker và các context BR/Plan/Case/UT đã kiểm tra đều dùng:

```java
articleData.setFavoritesCount(countMap.get(articleData.getId()));
```

ArticleData khai báo `private int favoritesCount`. Với mock chỉ có count cho article-1, Map thiếu article-2 sẽ trả null; truyền vào setter int gây NPE. Replay cục bộ xác nhận hành vi unboxing này. UT kỳ vọng NPE phù hợp với bản source GreyTest đã dùng.

Báo cáo của bạn mô tả source lúc chạy là:

```java
articleData.setFavoritesCount(countMap.getOrDefault(articleData.getId(), 0));
```

Với phiên bản này, diff sửa UT sang gọi thành công và assert article-2 bằng 0 là phù hợp. Tuy nhiên, áp diff đó lên bản `Map.get` mà GreyTest đang lưu vẫn sẽ lỗi. Chưa kiểm tra được trực tiếp file `src/main/` tại nơi bạn chạy coverage; hiện xác nhận được sự khác nhau giữa mô tả source lúc chạy và source trong hệ thống, chưa xác định ai/thao tác nào tạo ra khác biệt.

ZIP export hiện chứa test và script chạy, không kèm kiểm tra fingerprint source. Vì vậy test có thể được chạy trên một bản source khác mà không có bước phát hiện chênh lệch trước khi build.

## 3. Replay validator

Đã lấy ba UT thật từ database và context thật tương ứng, khôi phục validationSourceCode từ full source trong log. Mỗi lần replay chỉ giữ case đang kiểm tra trong approvedTestCases để không vướng kiểm tra số lượng response của cả batch; giữ nguyên source, fixture, rule và plan liên quan.

| Case | API validator | Semantic validator |
|---|---|---|
| 2360: getter sai | Không báo lỗi | Không báo lỗi |
| 2312: assertion following ngược | Không báo lỗi | Không báo lỗi |
| 2310: kỳ vọng NPE trên source Map.get | Không báo lỗi | Không báo lỗi; phù hợp source được sinh |

Đây là kiểm tra trực tiếp bằng classes backend đã build, không gọi LLM. Chưa chạy lại toàn bộ Gradle project. Compile probe chỉ kiểm tra ProfileData/getter; trace PREV và null-unboxing là kiểm chứng nhỏ, không thay thế chạy nguyên test suite của source mẫu.

`TEST_GENERATED` hiện có nghĩa đã sinh/lưu và qua các validator tĩnh hiện có; không có nghĩa đã compile và chạy thành công. Build/test thật được thực hiện sau khi tải ZIP bằng script coverage.

## 4. Hai hướng xử lý

1. **Sửa các bước dùng chung trong GreyTest, ưu tiên:** bổ sung contract API từ khai báo DTO/Lombok; phát hiện lời gọi accessor sai khi đủ bằng chứng; giữ mapping fixture ID → thứ tự kết quả → giá trị assertion theo TC; nạp đúng mapper/SQL include cần thiết và truyền evidence cần dùng sang các bước sau; đối chiếu fingerprint source trước khi chạy artifact. Mỗi nhóm phải có regression test trên nhiều source, tránh hard-code ProfileData hoặc riêng project RealWorld. Validator tĩnh vẫn có giới hạn, cần script Maven/Gradle xác nhận test chạy thật.
2. **Thêm vòng phản hồi từ build/test:** chạy trên đúng bản source trong môi trường thực thi tách biệt hoặc runner cục bộ hiện có, gửi lỗi compile/test về bước sửa UT rồi kiểm tra lại. Bắt được nhiều lỗi runtime hơn nhưng thêm thời gian, chi phí và công việc quản lý môi trường. Không nên chạy source người dùng trực tiếp trong process/backend chính.

Khuyến nghị bắt đầu hướng 1 với getter và sự nhất quán TC → assertion, đồng thời yêu cầu cùng phiên bản source khi chạy coverage. Chỉ mở rộng vòng tự sửa dựa trên thực thi sau khi thống nhất phạm vi; tăng thêm prompt hoặc đọc thêm mọi folder không đủ để ngăn các lỗi đã xác nhận.

Evidence cục bộ: `.greytest-generation-verification/source-context-audit-734/` tại workspace, gồm contexts.json, context gốc, unit-tests.json, replay-results.log, getter-bad-compile.log và getter-good-compile.log.
