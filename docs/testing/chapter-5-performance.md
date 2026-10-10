# Hiệu năng và quy mô — số đo ngày09/10/2026

Nguồn giữ nguyên: `outputs/chapter-5/system-20261009/run2/summary.json`, `read-c{1,3,5,10}.json`, source/scale ZIP cùng SHA256 trong summary. Dữ liệu này thực sự đã chạy trước gián đoạn; ngày10/10 không chạy lại để tạo số đo giả. API/DB/storage thật, provider fixture xác định; không đo latency hoặc tải LLM. Đo loopback cùng máy, không đại diện production/network hoặc nhiều client phân tán.

100request mỗi mức concurrent client, xen kẽ GET analysis/traceability, warm-up10request mỗi mức (không đưa vào sample); tổng400observation, lỗiHTTP0/400. p95 dùng nearest rank ceil(.95n), median chuẩn, throughput100/wall-clock của từng mức.

| Concurrent | n | Median ms | p95 ms | Throughput req/s | Error |
|---:|---:|---:|---:|---:|---:|
|1|100|25.12|38.28|40.92|0%|
|3|100|20.98|32.44|151.71|0%|
|5|100|12.42|29.75|327.80|0%|
|10|100|15.12|78.78|468.59|0%|

| Production classes/methods | Repeats | Analyze min/median/max ms | Đúng số lượng | Parse fail |
|---:|---:|---:|---:|---:|
|10|3|111.35/130.24/177.77|3/3|0|
|100|3|485.55/1013.90/1124.31|3/3|0|
|500|3|2453.15/2948.97/5307.04|3/3|0|

Fixture scale nhân các service một method, giữ existing test riêng. Đây là đo thời gian phân tích và correctness count trên cấu trúc đơn giản, không chứng minh khả năng phân tích mọi kiến trúc Java. Không có kết luận hệ thống chịu tải cực đại: chạy10client là mức đã đo, chưa tìm saturation hoặc soak/leak. Dải thời gian khác nhau do warm-up/cache và thực thi cùng máy; cần trình bày cả3repeat thay vì chọn lượt nhanh nhất. Corpus OSS thực nằm trong `chapter-5-corpus-analysis.md`, không gộp thời gian corpus đang chạy song song vào sample này.

Chưa sinh biểu đồ PNG: Python bundled không có matplotlib, chưa cài thêm dependency. Raw observations và các bảng trên đủ tái tạo biểu đồ sau. Coverage50→100 trong controlledflow là số đo chức năng, không phải độ chính xác AI; số liệu AI và UAT báo cáo riêng.
