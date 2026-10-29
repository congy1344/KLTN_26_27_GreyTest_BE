# Runner coverage không phụ thuộc Maven trên máy sinh ZIP — 2026-10-22

## Kết quả kiểm tra

`greytest-jacoco.init.gradle` hiện chỉ áp dụng JaCoCo và cấu hình đường dẫn XML tương đối trong build directory. File này không gọi Maven, không ghi đường dẫn Maven của máy phát triển. Maven repository trong Gradle cũng không phải chương trình Maven.

Điểm yếu nằm ở `run-greytest-coverage.cmd` / `.sh`: chưa xác thực tool chạy được, thiếu fallback khi Maven không có trong PATH, và bản Windows thêm mọi Maven tìm được trong `.tools` vào PATH trước khi kiểm tra.

## Luồng đã sửa

1. Kiểm tra JDK từ `JAVA_HOME` hoặc `java`/`javac` trong PATH trước khi build hoặc tải Maven.
2. Maven ưu tiên wrapper của project/module cha; wrapper thiếu metadata hoặc chạy lỗi phải báo rõ và dừng, không âm thầm đổi phiên bản.
3. Khi project không có wrapper, dùng Maven chạy được trong PATH, rồi thử riêng từng bản portable trong `.tools/apache-maven-*/bin` tại module và hai cấp cha. Chỉ bản đã xác thực mới được truyền vào PATH của tiến trình junction.
4. Khi chưa có Maven dùng được, chạy Apache Maven Wrapper 3.3.4 đi kèm ZIP trong `.greytest/maven`, pin Maven 3.9.14 theo wrapper sẵn có của GreyTest. Wrapper tải distribution về cache người dùng; các lần sau tái sử dụng cache. Không ghi đè `mvnw` hoặc `.mvn` của source.
5. Lỗi bootstrap thông báo kiểm tra JDK, network/proxy, distributionUrl, ZIP đầy đủ hoặc Maven portable. Cài parent POM dùng cùng tool đã chọn và không nuốt lỗi.
6. Gradle dùng wrapper/tool Gradle của project, không khởi động Maven fallback. Nếu thiếu Gradle, hướng dẫn khôi phục wrapper đầy đủ hoặc cài đúng version project.

Giữ bản vá junction cho Unicode. Bản wrapper Windows đi kèm có sửa được đánh dấu để truyền đường dẫn qua biến môi trường thay vì chèn vào chuỗi PowerShell, hỗ trợ tên như `O'Brien`. Shell hỗ trợ chuyển `JAVA_HOME` khi chạy qua Git Bash/MSYS. License/NOTICE và provenance được giữ cùng resource.

## Kiểm chứng

- Trước sửa: 8 tests bootstrap, 6 failures, gồm PATH hỏng, parent wrapper, thiếu JDK, lỗi download và fallback shell.
- Sau sửa: **76 tests liên quan đạt**, gồm **13 tình huống bootstrap** thực thi runner. Download/cache/failure sử dụng HTTP server loopback và distribution fixture, không phụ thuộc mạng Internet. Có Windows path chứa khoảng trắng, tiếng Việt và dấu nháy đơn; Maven local hỏng không che tool tốt; Gradle không tải Maven.
- Project Maven thật trong `../.greytest-runtime/portable-maven-smoke`: loại Maven khỏi PATH, runner chọn wrapper dự phòng và cache Maven thật; **1 test đạt**, exit 0, JaCoCo XML hợp lệ.
- Bản sao RealWorld Gradle với runner mới: **72 tests, 0 failures/errors/skipped**, exit 0, XML mới hợp lệ; không gọi Maven fallback. Chỉ thay runner trên bản sao, giữ nguyên Java source/test của bản sao.
- Compile backend cuối đạt; reviewer chỉ đọc không còn phát hiện cần sửa; `git diff --check` đạt cho các file hiện có được sửa.
- Evidence: `../.greytest-generation-verification/portable-build-tools/`: `bootstrap-red.log`, `bootstrap-targeted-final.log`, `final-compile.log`, `real-maven-smoke.log`, `real-gradle-smoke.log` và các file exit tương ứng.

## Áp dụng và giới hạn

Rebuild backend rồi tải ZIP mới. Giải nén đầy đủ, bao gồm thư mục `.greytest`; README trong ZIP có hướng dẫn tool và parent POM mới.

Máy người dùng vẫn cần JDK đúng với project. Lần đầu tải build tool/dependency cần Internet hoặc proxy phù hợp; offline cần cache hoặc Maven portable đã chuẩn bị. Không tự chọn phiên bản Gradle hay tự cài JDK. Đã chạy shell bằng Git Bash trên Windows, chưa kiểm chứng native Linux/macOS. Bootstrap tải mới được kiểm chứng qua loopback; lượt Maven thật dùng cache có sẵn, không phải thử cold-download HTTPS trên một máy hoàn toàn mới. Chưa chạy toàn bộ backend hoặc rebuild container trong lượt này.
