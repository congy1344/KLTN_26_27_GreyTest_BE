# Dataset chương 5 — preflight ngày 09/10/2026

Trạng thái: **FROZEN_SOURCE / HUMAN_REVIEW_PENDING / BUILD_NOT_EXECUTED**. Đây là chuẩn bị corpus, không phải kết quả thực nghiệm. Manifest máy đọc: `scripts/evaluation/datasets.json`; bản bằng chứng ngoài Maven target: `outputs/chapter-5/dataset-preflight-20261009/provenance.json` tại workspace.

Đã lấy năm repository public độc lập, giữ exact commit và SHA-256 license/source service. Clone dùng `--depth 1 --no-checkout`, chỉ materialize Java, POM/Gradle, README, license, wrapper launcher và OpenAPI. Không đọc hoặc materialize `.env`; không chạy script/build upstream. Đây là source snapshot phục vụ phân tích, **chưa phải working tree build đầy đủ**: cần bổ sung wrapper distribution/config, resources/fixtures an toàn vào build workspace riêng.


> **Cập nhật ngày 10/10/2026:** trạng thái FROZEN_SOURCE / HUMAN_REVIEW_PENDING / BUILD_NOT_EXECUTED và các class/method null trong mô tả bên dưới là snapshot preflight ngày09/10, giữ lại làm provenance. Hiện [AST API readiness](./chapter-5-corpus-analysis.md) đã chạy trên cả năm dự án: parsed1934/1934 production files, failed0; có class/method/endpoint counts thực trong bảng riêng, không coi parse đủ là extraction accuracy. [Build preflight](./chapter-5-build-preflight.md) đã biên dịch bốn corpus (PiggyMetrics, PetClinic REST, mall, Shopizer); Realworld bị chặn tải đúng Gradle7.4 từ hai nguồn chính thức do TLS. Chưa chạy upstream tests hoặc verify generated JUnit5. Human oracle vẫn pending và cả năm ready_for_paid_runs=false; cập nhật này không thay đổi BR/ground truth hoặc source snapshot ban đầu.
## Chọn quy mô và scope trước thực nghiệm

Trong preflight đã đếm corpus rồi chốt ngưỡng quy mô; ngưỡng được chốt trước lượt thực nghiệm AI, không khẳng định chọn trước khi biết source counts. Source staging có `{id}-staged-source.zip` và `{id}-inventory.json` trong folder bằng chứng. Inventory ghi SHA-256 materialized file, Git blob frozen và cách đối chiếu raw bytes hoặc Git autocrlf normalization: Windows checkout có thể đổi LF/CRLF; không coi archive byte-identical với Git tree khi EOL đã chuyển. Manifest ghi `source_tree_commit` và `checkout_head` riêng. Năm empirical snapshot dùng HEAD trùng commit; golden original dùng commit riêng như mô tả dưới.

Phân loại sơ bộ theo số **production Java file**, không gọi là số class: nhỏ <80; vừa 80–299; lớn >=300. Ngưỡng là quy ước riêng của thực nghiệm, không phải chuẩn chung. Đếm toàn repository trong `*/src/main/java`; scope thực nghiệm sẽ chỉ chọn các method dưới đây. Với dự án lớn, kết quả chỉ đại diện scope đã chọn. Ngưỡng không được đổi sau khi xem output AI.

Đếm số file test dưới `*/src/test/java`. Đếm physical lines bằng split LF, gồm comment/blank/trailing segment. `@Service` là số file chứa regex lexical `@Service\b`, chưa phải AST. Không tính source trong target/build. **Class/method/endpoint counts để null**, chờ GreyTest phân tích qua API và lưu AnalysisManifest thật; không dùng regex làm số liệu parser. Test ở đường dẫn khác chuẩn (ví dụ mall-admin/src/test/com) không nằm trong test-file count.

| ID | Exact commit | Java / Boot | License | Production files / lines | Test files / service annotation files | Quy mô |
|---|---|---|---|---|---|---|
| piggymetrics | `6bb2cf9ddbca980b664d3edbb6ff775d75369278` | 8 / 2.0.3.RELEASE | MIT | 72 / 2749 | 25 / 8 | Nhỏ |
| petclinic-rest | `26a80b7a510af1fda0acdfb45d65aa480bdb7607` (v3.4.0) | 17 inherited Boot parent; effective POM pending / 3.4.0 | Apache-2.0 | 83 / 5534 | 18 / 2 | Vừa |
| realworld | `ee17e31aafe733d98c4853c8b9a74d7f2f6c924a` | 11 / 2.6.3 | MIT | 93 / 3981 | 23 / 7 | Vừa |
| mall | `dcaa93b3150352e5044708d7211b5bed0af4509f` | 17 / 3.5.14 | Apache-2.0 | 519 / 85561 | 4 / 48 | Lớn |
| shopizer | `6a4a0a65a3408ee8f62597b51d1b3aac24b77dee` | 11 in POM; README mentions 11/17 / 2.5.12 | Apache-2.0 | 1167 / 109317 | 37 / 98 | Lớn |

Nguồn upstream đã kiểm tra qua trình duyệt và Git: [PiggyMetrics](https://github.com/sqshq/piggymetrics), [PetClinic REST v3.4.0](https://github.com/spring-petclinic/spring-petclinic-rest/tree/v3.4.0), [RealWorld Spring Boot](https://github.com/gothinkster/spring-boot-realworld-example-app), [mall](https://github.com/macrozheng/mall), [Shopizer](https://github.com/shopizer-ecommerce/shopizer). License thực được đọc từ Git snapshot (LICENCE, LICENSE.txt, LICENSE hoặc LICENSE.md tương ứng); URL commit và hash có trong manifest. Một số URL GitHub blob commit không render được trong web tool; việc xác nhận license dựa vào file Git đã fetch, không suy đoán từ tên repo.

## Scope dự kiến và recipe build chưa chạy

| Project | Service/method đề xuất | Recipe sau khi chuẩn bị build workspace | Điều kiện cần kiểm tra |
|---|---|---|---|
| PiggyMetrics | AccountServiceImpl.findByName, create | JDK8: `mvn -pl account-service -am test` | JUnit4 upstream; generated JUnit5 cần harness/dependency riêng. Mock repository/AuthServiceClient, không gọi dịch vụ thật. Upstream integration có MongoDB/config dependencies. |
| PetClinic REST | ClinicServiceImpl.findOwnerByLastName, saveOwner | JDK17: `mvn clean test` | Maven generates OpenAPI/MapStruct; giữ generated code khỏi production measurement. Resources seed HSQLDB/H2 cần restore trước baseline. |
| RealWorld | TagsQueryService.allTags; mở rộng UserService sau parser pilot | JDK11: `./gradlew test` (Windows: `gradlew.bat test`) | Wrapper jar/properties chưa materialize, Gradle cache/network và sqlite fixture cần chuẩn bị. allTags ít nhánh: công bố hạn chế khi so sánh coverage. |
| mall | PmsBrandServiceImpl.listAllBrand, getBrand, createBrand | JDK17: `mvn -pl mall-admin -am test` | DB/cache/search config external; unit scope dùng mapper mock. Interface chỉ mô tả nghiệp vụ mức cao, chưa đủ oracle cho fallback/default/error/boundary. |
| Shopizer | ShoppingCartServiceImpl.createShippingProduct | JDK11/17: `mvn -pl sm-shop -am test` | Multi-module, JUnit mixed; chỉ method domain với fixture/mocks cho local generation pilot. Không chạy app/API toàn hệ thống trong corpus pilot. |

Recipe là dự kiến, không gán Pass khi chưa chạy. Có @Service thực và Java source target <=21 chưa chứng minh JavaParser phân tích hết, generated test biên dịch, hoặc dependency resolve thành công.

## Bản nháp yêu cầu độc lập và test thủ công

Tất cả dưới đây **HUMAN_REVIEW_PENDING**. Nguồn là test upstream/API/interface contract có trước đầu ra AI; không dùng implementation làm oracle duy nhất. Hai sinh viên rà soát nguồn, thống nhất method scope/precondition/input/assertion và ký tên/ngày trước khi coi là ground truth. Nguồn upstream cũng có thể sai hoặc thiếu: bất đồng phải ghi nhận, không âm thầm sửa expected cho khớp source. Các mã dưới đây là ID draft riêng, không phải BR đã approved trong DB.

| BR draft | Nội dung và case thủ công draft | Nguồn độc lập frozen |
|---|---|---|
| PM-01 | findByName với tên rỗng: input `""`; kỳ vọng IllegalArgumentException; không gọi mạng. | [AccountServiceTest.shouldFailWhenNameIsEmpty](https://github.com/sqshq/piggymetrics/blob/6bb2cf9ddbca980b664d3edbb6ff775d75369278/account-service/src/test/java/com/piggymetrics/account/service/AccountServiceTest.java) |
| PM-02 | create user chưa tồn tại: username `test`, repository báo không tồn tại, Auth mock thành công; Account.name=`test`, Saving amount/interest=0, deposit/capitalization=false, default currency, lastSeen có giá trị; Auth create và repository save một lần. Không suy diễn duplicate/null policy từ implementation. | [AccountServiceTest.shouldCreateAccountWithGivenUser](https://github.com/sqshq/piggymetrics/blob/6bb2cf9ddbca980b664d3edbb6ff775d75369278/account-service/src/test/java/com/piggymetrics/account/service/AccountServiceTest.java) |
| PC-01 | findOwnerByLastName với fixture upstream chứa 2 Davis: input `Davis` → 2 owner; `Daviss` → tập rỗng. Khi chuyển unit scope, mock repository giữ cùng dữ liệu, không dùng ID/name không có trong fixture. | [AbstractClinicServiceTests.shouldFindOwnersByLastName](https://github.com/spring-petclinic/spring-petclinic-rest/blob/26a80b7a510af1fda0acdfb45d65aa480bdb7607/src/test/java/org/springframework/samples/petclinic/service/clinicService/AbstractClinicServiceTests.java) |
| PC-02 | saveOwner với owner hợp lệ Sam Schultz, địa chỉ/điện thoại từ fixture test: sau persistence, ID khác0 và số owner Schultz tăng1. Đây là yêu cầu persistence; cần review cách chuyển assertion sang unit mock, không coi verify(save) một mình là chứng minh ID được lưu. | [AbstractClinicServiceTests.shouldInsertOwner](https://github.com/spring-petclinic/spring-petclinic-rest/blob/26a80b7a510af1fda0acdfb45d65aa480bdb7607/src/test/java/org/springframework/samples/petclinic/service/clinicService/AbstractClinicServiceTests.java) |
| RW-01 | Sau khi có article với tag `java`, allTags chứa `java`. Unit draft mock TagReadService trả `[java]`, assert contains java. Không tự thêm thứ tự/dedup/case-sensitive policy. | [TagsQueryServiceTest.should_get_all_tags](https://github.com/gothinkster/spring-boot-realworld-example-app/blob/ee17e31aafe733d98c4853c8b9a74d7f2f6c924a/src/test/java/io/spring/application/tag/TagsQueryServiceTest.java) |
| MA-01 | listAllBrand phải trả toàn bộ brand trong scope; draft mapper fixture hai brand → kết quả có hai brand. | [PmsBrandService contract: 获取所有品牌 / lấy toàn bộ thương hiệu](https://github.com/macrozheng/mall/blob/dcaa93b3150352e5044708d7211b5bed0af4509f/mall-admin/src/main/java/com/macro/mall/service/PmsBrandService.java) |
| MA-02 | getBrand với ID tồn tại: trả brand tương ứng. Draft mapper fixture id42 → brand42. Không chốt missing/null/default/pagination policy trước human review; createBrand chưa có expected fields/defaults độc lập nên chưa nhập ground truth. | [PmsBrandService contract: 获取品牌详情 / lấy chi tiết thương hiệu](https://github.com/macrozheng/mall/blob/dcaa93b3150352e5044708d7211b5bed0af4509f/mall-admin/src/main/java/com/macro/mall/service/PmsBrandService.java) |
| SH-01 | createShippingProduct với cart chỉ chứa virtual products: expected null theo JavaDoc. Draft một virtual product, quantity1; assert null. Đây là assertion dễ nhầm với empty list, cần con người xác nhận contract. | [ShoppingCartService.createShippingProduct JavaDoc](https://github.com/shopizer-ecommerce/shopizer/blob/6a4a0a65a3408ee8f62597b51d1b3aac24b77dee/sm-core/src/main/java/com/salesmanager/core/business/services/shoppingcart/ShoppingCartService.java) |
| SH-02 | createShippingProduct tạo danh sách ShippingProduct từ cart sản phẩm vật lý. Draft một sản phẩm physical/shippable với quantity2; cần review expected quantity/finalPrice và trường hợp mixed trước đóng băng vì JavaDoc không đủ chi tiết. | [ShoppingCartService contract](https://github.com/shopizer-ecommerce/shopizer/blob/6a4a0a65a3408ee8f62597b51d1b3aac24b77dee/sm-core/src/main/java/com/salesmanager/core/business/services/shoppingcart/ShoppingCartService.java) |

Shopizer còn có [ShoppingCartAPIIntegrationTest](https://github.com/shopizer-ecommerce/shopizer/blob/6a4a0a65a3408ee8f62597b51d1b3aac24b77dee/sm-shop/src/test/java/com/salesmanager/test/shop/integration/cart/ShoppingCartAPIIntegrationTest.java) mô tả tạo cart và quantity; chỉ dùng làm bối cảnh hệ thống, không ép HTTP201 thành expected result của method createShippingProduct.

## Loại trừ và gate trước 75 lượt

- Original spring-projects/spring-petclinic commit `b3ee2c53e76e9267f03551a7cd36b0983c859c56` đã fetch và restore Java/POM/license/README vào `corpus/petclinic-golden`. Không có @Service production tại commit đó; dùng riêng golden analysis test, không đếm vào năm OSS empirical. HEAD của clone vẫn là upstream current; working files materialize từ commit b3ee explicit, phải dùng explicit commit khi restore/đóng gói.
- PetClinic REST HEAD `afc8fc1d3b8ec8de69414482b3bd70a1da78cb95` dùng Boot4.1.1, chưa chốt inherited JDK. Giữ snapshot tham khảo, thay bằng tag3.4.0 cho corpus.
- Fixture commerce, springboots4test1 variants và transfer oracle không là các OSS độc lập.

Trước chạy 5 projects x3 methods x5 repeats: (1) AnalysisManifest thật và parse failures; (2) effective POM/JDK và baseline build local; (3) scope method/dependencies/test framework adapter frozen; (4) human signoff BR và case thủ công, riêng từng nguồn; (5) LLM-only/Prompt-based runner được kiểm chứng giữ đúng context; (6) pilot mỗi dataset biên dịch/chạy và phân loại failure; (7) cùng model/options và lưu usage/raw output/pre-HITL. Chưa đạt các gate thì `ready_for_paid_runs=false`, không tạo số liệu thay thế. User cho phép dùng model hiện tại không giới hạn ngân sách; việc thiếu human signoff vẫn phải ghi thật.

Không gọi provider, không chạy build/test upstream, không thực hiện UAT hoặc ký thay con người trong bước chuẩn bị này.
