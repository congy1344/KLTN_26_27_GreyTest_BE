# Source update performance optimization

Ngày đo: 2026-11-03. Baseline instrumentation: commit `fc2c5e5`;
shared snapshot/index và regression tests: commit `39a7622`.

## Phạm vi và kết quả đã đo

Tái sử dụng dependency index trong một source-update run, giữ nguyên thuật toán
diff/impact, prompt, semantic validator, định dạng fingerprint/provenance và quyền
review của người dùng. Không thay đổi schema, provider hoặc số case cần sinh.

Lỗi thiết kế đã xác nhận bằng counters: cache index cũ nằm **sau** bước đọc/hash
toàn source. `withInputSnapshot` lại tạo map riêng cho mỗi batch và lượt xác minh
trước commit, nên cache AST hit vẫn có thể đọc lại mọi Java file.

Bằng chứng đầy đủ: [benchmarks/source-update-session.json](benchmarks/source-update-session.json).
Thời gian dưới đây là số đo local, không phải cam kết tốc độ production.

### A. Thêm đúng một method: diff và fingerprint

Fixture cố định: 33 Java file mỗi snapshot, candidate chỉ thêm `added()` trả về 42.
Đo diff và 32 cặp input/output fingerprint trong các batch scope; không gọi DB/LLM.
Control và shared chạy xen kẽ trong cùng JVM, cùng compiled validator, một lượt
làm nóng cho mỗi mode và ba mẫu đo. Test so sánh toàn bộ fingerprint hai mode.
Các nhãn stage dùng chung context synthetic; đây không phải benchmark sinh bốn
loại artifact thật.

| Chỉ số mỗi mẫu | Verified-load control | Shared session |
| --- | ---: | ---: |
| Dependency load calls | 66 | 66 |
| Dependency walk/hash passes | 34 | 3 |
| Java reads bởi dependency index | 1.122 | 99 |
| Production Java parses bởi scanner | 66 | 66 |
| Walk riêng để đăng ký monitor | 0 | 1 |
| Full digest passes của session | 0 | 2 |
| Wall time median | 3.103,870 ms | 773,058 ms |

Ba wall time control: 2.989,983 / 3.103,870 / 3.589,635 ms; shared:
901,225 / 761,221 / 773,058 ms. Dependency reads giảm 91,2%; nếu cộng cả walk
scanner và guard, số lượt duyệt toàn cây được instrument là 36 → 8, không phải
34 → 3. Hai lượt parse AST baseline/candidate vẫn giữ nguyên để không bỏ sót
ảnh hưởng dependency/caller.

### B. Pipeline thật với PostgreSQL và provider xác định

Fixture nhỏ: một file Service, sáu method đổi constant 41 → 42; gọi thật
`generateAll`, sinh 24 đề xuất qua BR → TP → TC → UT. Provider test trả JSON
xác định, không gọi API bên ngoài. Một warmup và ba cặp cold/unchanged-repeat.

| Chỉ số | Baseline cold | Shared cold | Baseline repeat | Shared repeat |
| --- | ---: | ---: | ---: | ---: |
| Dependency load calls | 82 | 82 | 30 | 30 |
| Dependency walks / Java reads | 14 / 14 | 1 / 1 | 7 / 7 | 1 / 1 |
| Source digest passes | 15 | 7 | 7 | 6 |
| LLM calls | 7 | 7 | 0 | 0 |
| Đề xuất | 24 | 24 | 24 | 24 |
| Wall time median | 2.101,159 ms | 2.195,798 ms | 820,326 ms | 862,366 ms |

Provider cold chỉ chiếm vài ms. Fixture này **chưa chứng minh giảm tổng latency**:
shared cold chậm hơn khoảng 4,5%, repeat khoảng 5,1%. Không suy rộng mức cải thiện
microbenchmark sang toàn pipeline. Những lượt đọc/query/context/validation khác
vẫn giữ nguyên; snapshot proof có chi phí cố định. Benchmark pipeline không tính
Spring startup, upload, thời gian review của con người hoặc chạy test sinh ra.
Digest artifact chứa DB ID nên chỉ so cold/repeat trong cùng fixture; không lấy
DB ID khác nhau giữa hai JVM làm bằng chứng thay đổi ngữ nghĩa.

### C. Pipeline thật chỉ thêm một method

Test `benchmarkOneAddedMethodThroughAnalysisAndAllStages` chạy thật
`SourceUpdateService.analyzeUpdate` rồi `generateAll` trên cùng fixture baseline
sáu method; candidate giữ nguyên sáu method và chỉ thêm `added()`.
Kiểm tra đúng một impacted method, 4 provider calls và 5 đề xuất CREATE
(METHOD + BR + TP + TC + UT). Sáu method cũ không được đưa vào scope sinh mới.
Baseline dùng production `fc2c5e5` và cùng test method thêm vào worktree để đo;
hai mode đều chạy JVM test `-Xms32m -Xmx768m`, một warmup và ba mẫu. Bằng chứng:
[benchmarks/source-update-one-method.json](benchmarks/source-update-one-method.json).

| Chỉ số | Baseline | Shared |
| --- | ---: | ---: |
| Changed methods / provider calls / proposals | 1 / 4 / 5 | 1 / 4 / 5 |
| Dependency load calls | 24 | 24 |
| Dependency walks / Java reads | 10 / 10 | 3 / 3 |
| Full source digest passes | 10 | 12 |
| Monitor registration walks | 0 | 3 |
| Wall time median (Analyze + generateAll) | 1.602,597 ms | 1.868,157 ms |

Các mẫu baseline: 1.635,923 / 1.602,597 / 1.486,823 ms; shared:
1.868,157 / 1.651,274 / 2.277,167 ms. Trên fixture một file này, chi phí proof ở
ranh giới stage/run tăng số digest passes; median tổng chậm hơn khoảng 16,6%.
Kết quả chứng minh giảm dependency scan, **không chứng minh tăng tốc end-to-end**
cho mọi project hoặc cho trường hợp một method rất nhỏ. Giữ kiểm tra đầy đủ theo
yêu cầu correctness; không bỏ proof để làm đẹp benchmark.

### D. Pipeline thật, vẫn một method thay đổi nhưng 129 file source

Giữ nguyên fixture C và thêm 128 class không liên quan, giống hệt nhau ở base và
candidate. Chỉ `added()` thay đổi; test xác nhận số file đầu vào, một impacted
method, 4 provider calls và 5 CREATE proposals. Cùng cấu hình JVM, một warmup và
ba sample cho mỗi phiên bản. Bằng chứng:
[benchmarks/source-update-one-method-scale.json](benchmarks/source-update-one-method-scale.json).

| Chỉ số | Baseline | Shared |
| --- | ---: | ---: |
| Java files mỗi snapshot | 129 | 129 |
| Changed methods / LLM calls / proposals | 1 / 4 / 5 | 1 / 4 / 5 |
| Dependency walks | 10 | 3 |
| Java reads bởi dependency index | 1.290 | 387 |
| Full source digest passes | 10 | 12 |
| Wall time median (Analyze + generateAll) | 6.926,673 ms | 3.658,069 ms |

Ba mẫu baseline: 6.862,575 / 7.041,448 / 6.926,673 ms; shared:
3.549,956 / 3.658,069 / 3.698,760 ms. Median giảm khoảng **47,2%** trên workload
này. Lợi ích đến từ tránh đọc/hash/stat/config-check trước cache lookup cho mọi
batch; full digest proofs vẫn giữ và có thể nhiều hơn baseline khi chỉ một batch
mỗi stage. Không đánh đồng số dependency walks với tổng mọi lượt duyệt cây.

Đây là service synthetic có nhiều file không liên quan, không phải source thật
của người dùng. Số đo kiểm tra được lợi ích khi tăng kích thước snapshot và giữ
nguyên phần thay đổi; không cam kết mức tăng tốc tương tự trên mọi project.

## Shared snapshot: identity, lifecycle và concurrency

`SourceSnapshotSession` có lifetime bằng một request Analyze hoặc một generation
run đang giữ lease. `generateAll` dùng cùng session cho cả bốn stage; sinh riêng
từng stage, retry và resume mở session mới và xác minh lại source/classpath.

1. Xác nhận canonical root, file identity và SHA-256 của toàn snapshot. Đăng ký
   monitor trước khi đọc digest; không lấy digest trong DB làm bằng chứng duy nhất.
2. Cache key index gồm root, full snapshot digest, digest Java source, classpath
   digest, trạng thái Lombok config và analyzer version `source-dependency-index-v2`.
   Full snapshot digest bao gồm DTO/entity, resource, POM và Lombok config.
3. Giữ reference index trong session dùng chung của `GenerationJobContext`.
   Nested batch input scopes vẫn đọc artifact/upstream mới; chỉ index và source
   digest được tái sử dụng xuyên scope. Monitor được kiểm tra cả khi batch-map hit.
4. JavaParser AST/solver có mutable caches, nên không gọi đó là object immutable.
   Map session dùng monitor lock; resolve dependency/output và resource evidence
   trên index dùng synchronization. Worker không chia sẻ `analysisModule` mà
   không giữ lock. Kết quả closure trả bản sao, provenance không bị chỉnh sửa.
5. Theo dõi source, resource, file cấu hình classpath, JAR/external build inputs,
   model repository và Lombok config kể cả config chưa tồn tại ở thư mục cha.
   Root bị thay thế, monitor mất directory hoặc overflow làm run thất bại.
6. WatchService có thể báo trễ. Vì vậy hash lại source và external files, đồng
   thời xác minh classpath **trước khi ghi stage SUCCEEDED** và trước khi run kết
   thúc. Đây là tính bất biến theo giao dịch: thay đổi giữa run không được công
   nhận là stage thành công; không phải OS ACL ngăn mọi process khác ghi file.
   Không dùng timestamp/size làm bằng chứng content không đổi.
7. Mỗi batch vẫn chạy semantic/reference/output dependency validation. Full-source
   dependency walk chỉ diễn ra một lần mỗi root/session; full digest proofs ở
   ranh giới stage/run có số lượng cố định, không tăng theo số batch LLM.
8. Source/config đổi hoặc lỗi đọc proof được phân loại `SOURCE_ANALYSIS_UNRESOLVED`,
   không retry LLM. Run bị FAILED; artifact/checkpoint tạm vẫn phải được xác minh
   lại khi resume/Apply. Apply tiếp tục dùng guard/fingerprint/provenance hiện có.
9. Nếu WatchService hoặc đăng ký directory không được hỗ trợ, tắt session reuse
   và quay về verified-load cũ. Cache miss vẫn đọc/hash/parse an toàn. Không có
   persistent trusted shortcut dựa vào path hoặc timestamp.

Không tạo cache output mới và không biến cache provenance thiếu thành lỗi nội
dung LLM. Các gate BLOCKED/unresolved và logic cache hit/miss cũ tiếp tục hoạt
động. Test output hợp lệ và lời gọi method không tồn tại được chạy cả tuần tự và
song song để kiểm tra index dùng chung không làm mất lỗi thật.

## Instrumentation

Metrics dùng monotonic nanoseconds và tính cả lượt lỗi. Thời gian nested/worker
có thể chồng lấp; không cộng tất cả duration thành wall time.

| Thành phần | Metric chính |
| --- | --- |
| Create ZIP/GitHub, Analyze, Apply | `source.update.*Nanos` trong log có project/update ID |
| Toàn generation run, từng stage | `pipelineNanos`, `stage.*.wallNanos` trong checkpoint metrics |
| Parse/diff/impact | `source.parseNanos`, `source.diffNanos`, `source.impactNanos` |
| Production reads/parses/scans | `source.javaReads`, `source.javaParses`, `source.walks` |
| Full source digest | `source.digestPasses`, `source.digestNanos`, `analysisHashFiles` |
| Dependency load/build | `dependency.loads`, `dependency.loadNanos`, `dependency.buildNanos` |
| Dependency scans/hashes/parses | `dependency.walks`, `dependency.hashPasses`, `dependencySourceReads`, `dependencyAstParses` |
| Resource/classpath scans | `resource.walks`, `resource.reads`, `classpath.sourceWalks`, `classpath.bindingWalks` |
| Context/provider/validation | `context.*Nanos`, `llmNanos`, `validation.semanticNanos` |
| Parser chạy khi dựng context | `context.classParses`, `context.methodParses` |
| Input/output fingerprint | `fingerprint.inputCalls`, `fingerprint.outputCalls`, tương ứng `*Nanos` |
| Cache/checkpoint | `cache.hit/miss/invalid`, `checkpoint.skip/invalid`, `dependencyIndexHits/Misses` |
| Session guard | `snapshot.indexHits`, `snapshot.watchWalks`, `snapshot.verifyNanos`, `snapshot.fallback` |
| Phạm vi ảnh hưởng | `impact.methods`, `impact.artifacts`, `artifact.regenerated` |

Tổng server processing của một lần cập nhật có thể đối chiếu create + Analyze +
các generation run + Apply theo ID. Các request review, thời gian người dùng chờ
và transaction commit ở ngoài proxy method không được gộp thành một latency
end-to-end đã đo. Các resource walk là phạm vi resource/module, không đánh đồng
với full-source walk.

## Regression và cách chạy lại

Các test mới/cập nhật:

- `SessionImpactRegressionTest`: thêm method, sửa body, đổi signature, xóa method,
  đổi DTO/entity contract, POM và Lombok config; đối chiếu toàn diff/caller với
  verified-load, kể cả lần lặp dùng cache.
- `SourceSnapshotSessionTest`: nhiều nested batch/worker dùng một index; sửa source
  cùng kích thước rồi khôi phục timestamp; config/resource mới; đổi classpath;
  external input sửa cùng timestamp; retry mở lại session; lỗi đọc proof cuối;
  filesystem không hỗ trợ monitor quay về verified reads.
- `GenerationFingerprintServiceTest`: input/output proof giống control, invalid
  method vẫn bị phát hiện, nhiều worker luân phiên output đúng/sai không lẫn state.
- `IncrementalPipelineBenchmarkTest`: retry/resume, persistent cache recovery,
  protected edits, Apply và request trùng; provider sửa source ngay giữa request
  phải làm update FAILED, không stage nào SUCCEEDED và không gọi lại provider.

Ba assertion benchmark cũ đã lỗi ngay trên baseline: 5 thay vì 7 provider calls,
1 thay vì 2 cache hits, 18 thay vì 20 artifact sau một batch lỗi. Đã tái hiện trên
worktree `fc2c5e5` trước khi sửa test theo batch size 4 và hành vi executor cho
batch độc lập hoàn tất. Không sửa business logic để làm test pass.

```powershell
# PostgreSQL test riêng, LLM_PROVIDER=mock; cấp username/password qua environment.
$env:SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5433/greytest_incremental_test_20261024'
$env:LLM_PROVIDER='mock'
mvn -q '-Dbenchmark.api=all' '-Dbenchmark.phase=verification' test

# Control/shared cùng build; bằng chứng tại target/source-scan-paired.json.
mvn -q '-Dtest=SourceUpdateScanBenchmarkTest' '-Dsource.benchmark=true' '-Dbenchmark.phase=paired' test

# Pipeline chỉ thêm một method, một warmup và ba sample.
mvn -q '-Dtest=IncrementalPipelineBenchmarkTest#benchmarkOneAddedMethodThroughAnalysisAndAllStages' '-Dbenchmark.phase=after' test

# Cùng một method, fixture 129 file; dùng cùng test method trên baseline fc2c5e5.
mvn -q '-Dtest=IncrementalPipelineBenchmarkTest#benchmarkOneAddedMethodThroughAnalysisAndAllStages' '-Dbenchmark.supportingClasses=128' '-Dbenchmark.phase=after-scale128' test
```

Trên máy đo Windows, full suite đã có một lượt không đủ credential cho hai nhóm
DB test và một lượt hết pagefile khi các JVM con dùng heap mặc định. Sau đó chạy
với `SPRING_DATASOURCE_USERNAME`/`SPRING_DATASOURCE_PASSWORD` của DB test và giới
hạn bộ nhớ chỉ trong process kiểm thử:

```powershell
$env:JAVA_TOOL_OPTIONS='-Xms32m -Xmx512m'
$env:MAVEN_OPTS='-Xms32m -Xmx256m'
mvn -q '-DargLine=-Xms32m -Xmx768m' '-Dspring.test.context.cache.maxSize=4' '-Dbenchmark.api=all' '-Dbenchmark.phase=complete-suite' test
```

Không thay pagefile, production configuration hoặc dừng các process Java khác.

Full suite cuối: **145 test classes, 1.841 test: 1.837 pass, 4 skipped,
0 failure/error**, Maven exit 0. Chỉ tổng hợp Surefire XML thuộc lượt chạy từ
15:35:52 +07:00 để không lẫn report lịch sử của test đã bị xóa. Bốn skip gồm hai
oracle acceptance tests có điều kiện, một PetClinic golden manifest và một
microbenchmark opt-in; microbenchmark đã chạy riêng với flag và pass. Bằng chứng:
[benchmarks/source-update-verification.json](benchmarks/source-update-verification.json).
Reviewer read-only đã kiểm tra correctness/security/regression; lỗi phân loại
final digest read được reviewer phát hiện đã sửa và có regression test. Test ZIP
filesystem cũng đã bắt lỗi `ProviderMismatchException`; đăng ký monitor giờ
fallback đúng về verified-load. Reviewer không còn actionable finding.
Sau full suite, chỉ bổ sung kích thước fixture benchmark và chọn Service theo
qualified name thay vì thứ tự class. Benchmark 129 file trước/sau đều pass; không
có thay đổi production sau lượt full suite.

## Giới hạn

Không thay prompt để lấy tốc độ bằng cách giảm chất lượng. Không bỏ semantic gate
hay output dependency proof. Chưa benchmark latency với API LLM production hoặc
project thực tế của người dùng. Thời gian microbenchmark không chứng minh tốc độ
end-to-end; fixture sáu method nhỏ chưa cho thấy wall-time cải thiện. Filesystem
monitor fallback giữ correctness theo verified-load cũ nhưng không có lợi ích
giảm scan của session. Nên đo trên project thật trước khi kết luận lợi ích latency;
không suy luận lợi ích end-to-end chỉ từ số dependency scan giảm trên project nhỏ.
