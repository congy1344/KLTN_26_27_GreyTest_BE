# Trạng thái chương 5 — bản tổng hợp cuối ngày 10/10/2026

**Toàn kế hoạch chưa hoàn tất.** Đã có chiến lược5.1, bảng kết quả5.2 và metric thực5.3 để dùng theo phạm vi chứng minh; chưa thể ghi hoàn thành UAT hoặc toàn thực nghiệm AI.

| Phase | Kết quả | Phần còn lại |
|---|---|---|
|1Baseline|Hoàn tất; verification mới560Pass+3Skip/563 backend,0Failure/Error; frontend175Pass/build giữOct9; Python21Pass,ba self-checkPass|Golden riêng1Fail62/86methods chờ quyết định |
|2Strategy/catalog|Hoàn tất43masterTC/template|Không gán Pass cho master chưa đủ kỳ vọng |
|3Integration|Hoàn tất5IT+4controller9Pass|FixtureLLM không đánh giá AI thật |
|4System/flows|Hai HTTPflow, downloadedZIPreplay; UIhai chức năng chính từupload/analyze tớiBRPlanCaseapprove/Unit và trace|UIcoveragechooser/refinement/reportchưa xác minh;UAT hai người chưa thực hiện |
|5AI/corpus|Pilotlive1trial7calls;5OSS ASTready;4compilePass|RealworldcompileBlocked;humanoracle/manualcomparison; generatedtestpilot/baselinerunners;75runs;UMR/stability |
|6Perf/security|400read observations,9scale;securityvariants có evidence|Jobload và toàn bộ securitymaster còn thiếu;khôngSLAproduction |
|7Report|Draftv3 cập nhật raw/metric/hashlinks|Chờ finalreview và evidence cho phần chưa hoàn tất;chưa stage/commit |

[5.2 kết quả](./chapter-5-results.md), [5.3 đánh giá](./chapter-5-evaluation.md), [build preflight](./chapter-5-build-preflight.md), [CSV v3](D:/.KLTN/outputs/chapter-5/report-20261010-v3/test-results.csv), [index v3](D:/.KLTN/outputs/chapter-5/report-20261010-v3/artifacts-index.json).

CSVv1/v2 giữ nguyên. V3 có72hàng:43master(37NotRun,6Blocked),27biến thểPass,1goldenFail,1RealworldBlocked. Đây là hàng audit gồm master/biến thể, không phải72TC độc lập hoặc toàn bộ invocation.

Golden không sửa expected/parser khi chưa có quyết định. Security2expected400/actual201 là mismatchfixture, không tự kết luậnXXE;Security3haiHTTPentityvariants0listenerrequest, chưa bao phủfileentity/master. Branch50→100 là subsetcontrolled; priorfullsuite đã100%. BốncompilePass không thay generatedJUnit5/pilot hoặc humanoracle. Không bịa hai người UAT hay75lượtAI.
