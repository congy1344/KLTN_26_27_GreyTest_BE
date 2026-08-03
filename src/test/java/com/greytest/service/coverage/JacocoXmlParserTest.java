package com.greytest.service.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.greytest.service.coverage.JacocoXmlParser.ParsedMethod;
import com.greytest.service.coverage.JacocoXmlParser.ParsedReport;

class JacocoXmlParserTest {

    // DOCTYPE trỏ tới report.dtd giống file jacoco.xml thật — parser phải bỏ qua
    // được DTD thay vì cố tải file
    private static final String SAMPLE = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="demo">
              <package name="com/example">
                <class name="com/example/OrderService" sourcefilename="OrderService.java">
                  <method name="&lt;init&gt;" desc="()V" line="5">
                    <counter type="LINE" missed="0" covered="1"/>
                  </method>
                  <method name="createOrder" desc="(I)I" line="10">
                    <counter type="LINE" missed="3" covered="2"/>
                    <counter type="BRANCH" missed="1" covered="1"/>
                  </method>
                  <method name="cancelOrder" desc="()V" line="30">
                    <counter type="LINE" missed="0" covered="4"/>
                  </method>
                  <method name="lambda$createOrder$0" desc="()V" line="12">
                    <counter type="LINE" missed="1" covered="0"/>
                  </method>
                </class>
                <sourcefile name="OrderService.java">
                  <line nr="10" mi="0" ci="1" mb="0" cb="0"/>
                  <line nr="12" mi="1" ci="0" mb="1" cb="1"/>
                  <line nr="15" mi="2" ci="0" mb="0" cb="0"/>
                  <line nr="31" mi="0" ci="2" mb="0" cb="0"/>
                </sourcefile>
              </package>
              <counter type="LINE" missed="4" covered="7"/>
              <counter type="BRANCH" missed="1" covered="1"/>
            </report>
            """;

    private final JacocoXmlParser parser = new JacocoXmlParser();

    private static InputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parseTinhDungTongVaCoverageTungMethod() {
        ParsedReport report = parser.parse(stream(SAMPLE));

        assertEquals(11, report.totalLines());
        assertEquals(7, report.coveredLines());
        assertEquals(2, report.totalBranches());
        assertEquals(1, report.coveredBranches());

        // <init> và lambda$ bị bỏ qua
        assertEquals(2, report.methods().size());

        ParsedMethod create = report.methods().get(0);
        assertEquals("com.example.OrderService", create.qualifiedClassName());
        assertEquals("createOrder", create.methodName());
        assertEquals(new BigDecimal("40.00"), create.lineCoverage());
        assertEquals(new BigDecimal("50.00"), create.branchCoverage());
        // missed lines chỉ lấy trong khoảng [10, 30) của method
        assertEquals(List.of(12, 15), create.missedLines());
        assertEquals(List.of(12), create.missedBranches());

        ParsedMethod cancel = report.methods().get(1);
        assertEquals("cancelOrder", cancel.methodName());
        // không có counter BRANCH → coi như 100%
        assertEquals(new BigDecimal("100.00"), cancel.branchCoverage());
        assertTrue(cancel.missedLines().isEmpty());
    }

    @Test
    void parseTuChoiXmlKhongPhaiJacoco() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(stream("<?xml version=\"1.0\"?><pom></pom>")));
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(stream("khong phai xml")));
    }
}
