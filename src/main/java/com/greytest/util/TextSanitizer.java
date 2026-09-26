package com.greytest.util;

import java.util.regex.Pattern;

/**
 * Tiện ích làm sạch văn bản AI, chuẩn hóa và loại bỏ các ký tự chữ Hán / tiếng Trung
 * xuất hiện ngoài ý muốn khi AI dịch sang tiếng Việt.
 */
public final class TextSanitizer {

    private static final Pattern CHINESE_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]+");

    private TextSanitizer() {}

    /**
     * Thay thế các từ khóa lập trình tiếng Trung phổ biến thành tiếng Việt tự nhiên,
     * và loại bỏ các ký tự chữ Hán còn sót lại.
     */
    public static String cleanAiText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        String cleaned = text
                .replace("抛出异常", " ném ra ngoại lệ ")
                .replace("抛出", " ném ra ")
                .replace("扔出异常", " ném ra ngoại lệ ")
                .replace("扔异常", " ném ngoại lệ ")
                .replace("大于", " lớn hơn ")
                .replace("小于", " nhỏ hơn ")
                .replace("等于", " bằng ")
                .replace("异常", " ngoại lệ ")
                .replace("返回", " trả về ")
                .replace("如果", " nếu ")
                .replace("为空", " là null ")
                .replace("不为空", " không null ");

        // Nếu vẫn còn ký tự chữ Hán bất kỳ, thay bằng khoảng trắng
        if (CHINESE_PATTERN.matcher(cleaned).find()) {
            cleaned = CHINESE_PATTERN.matcher(cleaned).replaceAll(" ");
        }

        // Chuẩn hóa khoảng trắng kép và khoảng trắng trước dấu câu
        return cleaned
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" +([.,;:?!])", "$1")
                .trim();
    }
}
