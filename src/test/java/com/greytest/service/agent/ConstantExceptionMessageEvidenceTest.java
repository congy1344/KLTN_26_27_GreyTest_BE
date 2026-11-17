package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.greytest.dto.agent.GenerationContextDtos.MethodContextDto;

/** Hồi quy course-registration: message ghép hằng static final hoặc giá trị runtime, không dùng formatter. */
class ConstantExceptionMessageEvidenceTest {
    private static final String CREDITS_THROW =
            "throw new BusinessException(\"Chưa tích lũy đủ \" + CREDITS + \" tín chỉ để tốt nghiệp\");";

    @Test
    void acceptsAFragmentConcatenatedWithAConstant() {
        var classes = source("", "throw new BusinessException(\"Tiết học phải từ 1 đến \" + MAX_PERIOD);");
        assertThat(validate(classes, "service ném BusinessException với thông điệp \"Tiết học phải từ 1 đến \" + MAX_PERIOD."))
                .isEmpty();
    }

    @Test
    void acceptsEnglishFollowedByFragmentsOfAConcatenatedMessage() {
        // Câu AI thật viết cho course-registration (pilot 16/11); "followed by" tương đương "nối với".
        var classes = source("String code", "throw new BusinessException(\"Đã đăng ký môn \" + code + \" trong học kỳ này\");");
        assertThat(validate(classes, "the service throws BusinessException with the message \"Đã đăng ký môn \""
                + " followed by the requested course code and \" trong học kỳ này\".")).isEmpty();
        assertThat(validate(source("", "throw new BusinessException(\"Tiết học phải từ 1 đến \" + MAX_PERIOD);"),
                "throws BusinessException with message \"Tiết học phải từ 1 đến \" followed by MAX_PERIOD.")).isEmpty();
    }

    @Test
    void rejectsAWrongFragmentEvenWhenFollowedByARuntimeValue() {
        var classes = source("String code", "throw new BusinessException(\"Đã đăng ký môn \" + code + \" trong học kỳ này\");");
        assertThat(validate(classes, "throws BusinessException with message \"Đã hủy môn \" followed by the course code."))
                .isPresent();
    }

    @Test
    void acceptsTheMessageRenderedWithTheConstantValue() {
        assertThat(validate(source("", CREDITS_THROW),
                "service ném BusinessException với thông điệp \"Chưa tích lũy đủ 120 tín chỉ để tốt nghiệp\"."))
                .isEmpty();
    }

    @Test
    void rejectsAMessageRenderedWithAnotherConstantValue() {
        assertThat(validate(source("", CREDITS_THROW),
                "service ném BusinessException với thông điệp \"Chưa tích lũy đủ 130 tín chỉ để tốt nghiệp\"."))
                .hasValueSatisfying(error -> assertThat(error).contains("Chưa tích lũy đủ 120 tín chỉ để tốt nghiệp"));
    }

    @Test
    void parameterShadowingAConstantStaysARuntimeValue() {
        var classes = source("int CREDITS", CREDITS_THROW);
        assertThat(validate(classes, "service ném BusinessException với thông điệp \"Chưa tích lũy đủ 99 tín chỉ để tốt nghiệp\"."))
                .isEmpty();
    }

    @Test
    void templateClaimOnConcatenationPointsBackToThePlainMessage() {
        var classes = source("String room", "throw new BusinessException(\"Phòng \" + room + \" đã có lớp\");");
        assertThat(validate(classes, "service ném BusinessException với thông điệp chính xác từ mẫu \"Phòng <room> đã có lớp\" với tham số room."))
                .hasValueSatisfying(error -> assertThat(error)
                        .contains("does not use a formatter", "Phòng <room> đã có lớp")
                        .doesNotContain("Source formatter evidence"));
    }

    @Test
    void plainMessageWithRuntimePlaceholderIsAccepted() {
        var classes = source("String room", "throw new BusinessException(\"Phòng \" + room + \" đã có lớp\");");
        assertThat(validate(classes, "service ném BusinessException với thông điệp \"Phòng <room> đã có lớp\".")).isEmpty();
    }

    @Test
    void unknownMessageWithoutFormatterDoesNotSuggestAFormatTemplate() {
        var classes = source("", "throw new BusinessException(\"Vượt quá \" + INHERITED_LIMIT);");
        assertThat(validate(classes, "service ném BusinessException với thông điệp \"Vượt quá 5\"."))
                .hasValueSatisfying(error -> assertThat(error)
                        .contains("cannot verify the exact source exception message")
                        .doesNotContain("String.format", "format template"));
    }

    @Test
    void contradictedMessageIsNotCalledATemplate() {
        var classes = source("String room", "throw new BusinessException(\"Phòng \" + room + \" đã có lớp\");");
        assertThat(validate(classes, "service ném BusinessException với thông điệp \"Phòng \"."))
                .hasValueSatisfying(error -> assertThat(error)
                        .contains("Source message(s): [Phòng <room> đã có lớp]")
                        .doesNotContain("template"));
    }

    @Test
    void sourceFormatterKeepsTheFormatTemplateGuidance() {
        var classes = source("Long id", "throw new BusinessException(String.format(\"Thiếu %d\", id));");
        assertThat(validate(classes, "service ném BusinessException với thông điệp từ mẫu \"Thiếu %d\" với tham số otherId."))
                .hasValueSatisfying(error -> assertThat(error).contains("Source formatter evidence", "Thiếu %d"));
    }

    @Test
    void doubleConstantIsNotRenderedFromItsIntegerInitializer() {
        var classes = source("", "throw new BusinessException(\"GPA tối đa \" + MAX_GPA);");
        assertThat(validate(classes, "service ném BusinessException với thông điệp \"GPA tối đa 4.0\".")).isEmpty();
    }

    @Test
    void charConstantIsNotRenderedFromItsIntegerInitializer() {
        var classes = source("", "throw new BusinessException(\"Hạng \" + GRADE);");
        assertThat(validate(classes, "service ném BusinessException với thông điệp \"Hạng A\".")).isEmpty();
    }

    @Test
    void constantWithoutLiteralInitializerStaysARuntimeValueButKeepsLiteralParts() {
        var classes = source("", "throw new BusinessException(\"Không nhỏ hơn \" + MIN_SCORE);");
        assertThat(validate(classes, "service ném BusinessException với thông điệp \"Không nhỏ hơn -1\".")).isEmpty();
        assertThat(validate(classes, "service ném BusinessException với thông điệp \"Không lớn hơn -1\".")).isPresent();
    }

    @Test
    void stringConstantIsRenderedExactly() {
        var classes = source("", "throw new BusinessException(PREFIX + \"đã tồn tại\");");
        assertThat(validate(classes, "service ném BusinessException với thông điệp \"Mã đã tồn tại\".")).isEmpty();
        assertThat(validate(classes, "service ném BusinessException với thông điệp \"Tên đã tồn tại\".")).isPresent();
    }

    @Test
    void formatterOutsideTheExceptionMessageDoesNotSuggestAFormatTemplate() {
        var classes = source("String room, Long id", "System.out.println(String.format(\"Kiểm tra %d\", id)); "
                + "throw new BusinessException(\"Phòng \" + room + \" đã có lớp\");");
        assertThat(validate(classes, "service ném BusinessException với thông điệp từ mẫu \"Phòng <room> đã có lớp\" với tham số room."))
                .hasValueSatisfying(error -> assertThat(error).contains("does not use a formatter"));
    }

    @Test
    void dateFormatInsideTheMessageIsNotAMessageFormatter() {
        var classes = source("java.time.LocalDate date", "throw new BusinessException(\"Hạn \" + date.format(FMT) + \" đã qua\");");
        assertThat(validate(classes, "service ném BusinessException với thông điệp từ mẫu \"Hạn <date> đã qua\" với tham số date."))
                .hasValueSatisfying(error -> assertThat(error).contains("does not use a formatter"));
    }

    @Test
    void formatterStoredInALocalKeepsTheFormatTemplateGuidance() {
        var classes = source("Long id", "String message = String.format(\"Thiếu %d\", id); throw new BusinessException(message);");
        assertThat(validate(classes, "service ném BusinessException với thông điệp từ mẫu \"Thiếu %d\" với tham số otherId."))
                .hasValueSatisfying(error -> assertThat(error).contains("format template").doesNotContain("does not use a formatter"));
    }

    @Test
    void stringFormattedKeepsTheFormatTemplateGuidance() {
        var classes = source("Long id", "throw new BusinessException(\"Thiếu %d\".formatted(id));");
        assertThat(validate(classes, "service ném BusinessException với thông điệp từ mẫu \"Thiếu %d\" với tham số otherId."))
                .hasValueSatisfying(error -> assertThat(error).contains("format template").doesNotContain("does not use a formatter"));
    }

    private Optional<String> validate(List<ClassContextDto> classes, String description) {
        return new SourceExceptionMessageEvidence(classes).validate(classes, 1L, description, null, true);
    }

    private List<ClassContextDto> source(String parameters, String body) {
        String method = "void run(" + parameters + ") { " + body + " }";
        var dto = new MethodContextDto(1L, "sample.Service", "run", "void", List.of(), List.of(), "PUBLIC", method,
                1, 1, List.of(), List.of(), List.of());
        String declarations = "static final int MAX_PERIOD = 12; static final int CREDITS = 120; "
                + "static final double MAX_GPA = 4; static final char GRADE = 65; static final int MIN_SCORE = -1; "
                + "static final String PREFIX = \"Mã \"; "
                + "static class BusinessException extends RuntimeException { "
                + "BusinessException(String message) { super(message); } } ";
        return List.of(new ClassContextDto(1L, "sample", "Service", "sample.Service", "SERVICE", "Service.java",
                "package sample; class Service { " + declarations + method + " }", List.of(), List.of(dto)));
    }
}
