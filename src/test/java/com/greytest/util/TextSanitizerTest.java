package com.greytest.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextSanitizerTest {

    @org.junit.jupiter.api.Test
    void removesTheStoredTestabilityNoteAndKeepsTheRealText() {
        String stored = "Mô tả thật.\n[GreyTest testability: UNKNOWN] PRIVATE_HELPER_ENTRY_POINT @ A.java:1 run — ghi chú | Chọn entry point.";
        org.assertj.core.api.Assertions.assertThat(TextSanitizer.withoutTestabilityNote(stored)).isEqualTo("Mô tả thật.");
        org.assertj.core.api.Assertions.assertThat(TextSanitizer.cleanAiText(stored)).isEqualTo("Mô tả thật.");
        org.assertj.core.api.Assertions.assertThat(TextSanitizer.withoutTestabilityNote("Dòng đầu\n[GreyTest testability: BLOCKED] x\nDòng sau")).isEqualTo("Dòng đầu\nDòng sau");
        org.assertj.core.api.Assertions.assertThat(TextSanitizer.withoutTestabilityNote("Không có ghi chú")).isEqualTo("Không có ghi chú");
        org.assertj.core.api.Assertions.assertThat(TextSanitizer.withoutTestabilityNote(null)).isNull();
    }


    @Test
    void preservesQuotedExceptionMessagesAndCodeSpansVerbatim() {
        String input = "Ném \"Book not exist with id : \" và `id  :  1001` hoặc 'date : '.";
        assertThat(TextSanitizer.cleanAiText(input)).isEqualTo(input);
    }

    @Test
    void cleansOnlyProseAroundEscapedQuotesAndForeignLanguageLiterals() {
        String input = "抛出 \"返回  id : \\\"1001\\\"\" nếu lỗi .";
        assertThat(TextSanitizer.cleanAiText(input))
                .isEqualTo("ném ra \"返回  id : \\\"1001\\\"\" nếu lỗi.");
    }

    @Test
    void preservesFencedCodeIncludingWhitespaceAndPunctuation() {
        String input = "Ví dụ :\n```java\nthrow new Exception(\"id :  \" );\n```";
        assertThat(TextSanitizer.cleanAiText(input))
                .isEqualTo("Ví dụ:\n```java\nthrow new Exception(\"id :  \" );\n```");
    }

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
