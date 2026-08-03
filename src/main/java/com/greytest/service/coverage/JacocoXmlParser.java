package com.greytest.service.coverage;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Parser thuần cho file jacoco.xml: đọc counter tổng của report và coverage
 * từng method (kèm danh sách dòng/nhánh bị miss). Không đụng tới database.
 */
@Service
public class JacocoXmlParser {

    /** Kết quả parse toàn report. */
    public record ParsedReport(int totalLines, int coveredLines, int totalBranches, int coveredBranches,
            List<ParsedMethod> methods) {
    }

    /** Coverage của một method trong report. */
    public record ParsedMethod(String qualifiedClassName, String methodName, int firstLine,
            BigDecimal lineCoverage, BigDecimal branchCoverage,
            List<Integer> missedLines, List<Integer> missedBranches) {
    }

    private record Counter(int missed, int covered) {
        int total() {
            return missed + covered;
        }
    }

    public ParsedReport parse(InputStream xml) {
        Document doc = readDocument(xml);
        Element root = doc.getDocumentElement();
        if (root == null || !"report".equals(root.getTagName())) {
            throw new IllegalArgumentException("File không phải JaCoCo XML hợp lệ");
        }
        Counter line = readCounter(root, "LINE");
        Counter branch = readCounter(root, "BRANCH");
        List<ParsedMethod> methods = new ArrayList<>();
        for (Element pkg : children(root, "package")) {
            parsePackage(pkg, methods);
        }
        return new ParsedReport(line.total(), line.covered(), branch.total(), branch.covered(), methods);
    }

    private Document readDocument(InputStream xml) {
        try {
            // jacoco.xml có DOCTYPE trỏ tới report.dtd nên không được cấm doctype,
            // chỉ tắt load DTD/external entity để tránh XXE
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(xml);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalArgumentException("File không phải JaCoCo XML hợp lệ", e);
        }
    }

    private void parsePackage(Element pkg, List<ParsedMethod> out) {
        // Gom dòng bị miss theo sourcefile để gán lại cho từng method theo khoảng dòng
        Map<String, List<Integer>> missedLinesByFile = new HashMap<>();
        Map<String, List<Integer>> missedBranchesByFile = new HashMap<>();
        for (Element sf : children(pkg, "sourcefile")) {
            String fileName = sf.getAttribute("name");
            for (Element ln : children(sf, "line")) {
                int nr = intAttr(ln, "nr");
                if (intAttr(ln, "mi") > 0) {
                    missedLinesByFile.computeIfAbsent(fileName, k -> new ArrayList<>()).add(nr);
                }
                if (intAttr(ln, "mb") > 0) {
                    missedBranchesByFile.computeIfAbsent(fileName, k -> new ArrayList<>()).add(nr);
                }
            }
        }
        for (Element cls : children(pkg, "class")) {
            String qualifiedName = cls.getAttribute("name").replace('/', '.');
            String sourceFile = cls.getAttribute("sourcefilename");
            List<Element> methodElements = children(cls, "method").stream()
                    .filter(m -> isRealMethod(m.getAttribute("name")))
                    .sorted(Comparator.comparingInt(m -> intAttr(m, "line")))
                    .toList();
            for (int i = 0; i < methodElements.size(); i++) {
                Element m = methodElements.get(i);
                int from = intAttr(m, "line");
                int to = i + 1 < methodElements.size() ? intAttr(methodElements.get(i + 1), "line") : Integer.MAX_VALUE;
                Counter line = readCounter(m, "LINE");
                Counter branch = readCounter(m, "BRANCH");
                out.add(new ParsedMethod(qualifiedName, m.getAttribute("name"), from,
                        percent(line), percent(branch),
                        slice(missedLinesByFile.get(sourceFile), from, to),
                        slice(missedBranchesByFile.get(sourceFile), from, to)));
            }
        }
    }

    private boolean isRealMethod(String name) {
        return !"<init>".equals(name) && !"<clinit>".equals(name) && !name.contains("$");
    }

    /** Lấy các dòng miss thuộc khoảng [from, to) của một method. */
    private List<Integer> slice(List<Integer> lines, int from, int to) {
        if (lines == null) {
            return List.of();
        }
        return lines.stream().filter(nr -> nr >= from && nr < to).sorted().toList();
    }

    /** Counter không tồn tại (vd method không có nhánh) coi như 100%. */
    private BigDecimal percent(Counter counter) {
        if (counter.total() == 0) {
            return BigDecimal.valueOf(100).setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(counter.covered() * 100.0 / counter.total()).setScale(2, RoundingMode.HALF_UP);
    }

    private Counter readCounter(Element parent, String type) {
        for (Element counter : children(parent, "counter")) {
            if (type.equals(counter.getAttribute("type"))) {
                return new Counter(intAttr(counter, "missed"), intAttr(counter, "covered"));
            }
        }
        return new Counter(0, 0);
    }

    /** Chỉ lấy element con trực tiếp đúng tag (tránh lẫn counter của cấp dưới). */
    private List<Element> children(Element parent, String tag) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element el && tag.equals(el.getTagName())) {
                result.add(el);
            }
        }
        return result;
    }

    private int intAttr(Element el, String name) {
        String value = el.getAttribute(name);
        return value.isEmpty() ? 0 : Integer.parseInt(value);
    }
}
