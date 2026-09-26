package com.greytest.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextSanitizerTest {

    @Test
    void translatesCommonChineseTermsToVietnamese() {
        String input = "Khi tìm phòng ban theo tên, nếu repository không trả về phòng ban nào khớp với tên đã cho thì抛出 DepartmentNotFoundException.";
        String output = TextSanitizer.cleanAiText(input);

        assertThat(output).isEqualTo("Khi tìm phòng ban theo tên, nếu repository không trả về phòng ban nào khớp với tên đã cho thì ném ra DepartmentNotFoundException.");

        // Dính chữ không có khoảng trắng như thì抛出DepartmentNotFoundException
        String attached = "nếu không tồn tại thì抛出DepartmentNotFoundException.";
        assertThat(TextSanitizer.cleanAiText(attached)).isEqualTo("nếu không tồn tại thì ném ra DepartmentNotFoundException.");

        // Các từ khác như 大于, 扔异常
        assertThat(TextSanitizer.cleanAiText("doctorId hợp lệ大于 0")).isEqualTo("doctorId hợp lệ lớn hơn 0");
        assertThat(TextSanitizer.cleanAiText("Tìm phòng ban theo tên扔异常 khi lỗi")).isEqualTo("Tìm phòng ban theo tên ném ngoại lệ khi lỗi");
    }

    @Test
    void removesAnyRemainingChineseCharacters() {
        String input = "Phương thức 返回 kết quả nếu không có lỗi gì.";
        String output = TextSanitizer.cleanAiText(input);

        assertThat(output).isEqualTo("Phương thức trả về kết quả nếu không có lỗi gì.");
        // Any residual Chinese character is replaced
        assertThat(TextSanitizer.cleanAiText("Kiểm tra 业务逻辑 hoàn tất.")).isEqualTo("Kiểm tra hoàn tất.");
    }

    @Test
    void preservesNormalVietnameseAndEnglishIdentifiers() {
        String input = "Thực hiện gọi statisticsClient.updateStatistics(...) và trả về DTO hợp lệ.";
        assertThat(TextSanitizer.cleanAiText(input)).isEqualTo(input);
    }
}
