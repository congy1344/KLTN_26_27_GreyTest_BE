package com.greytest.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.greytest.dto.AnalysisResultDto;
import com.greytest.dto.AnalysisManifestDto;
import com.greytest.dto.AnalysisManifestInput;
import com.greytest.dto.AnalysisManifestValidationDto;
import com.greytest.service.analysis.AnalysisManifestService;
import com.greytest.service.analysis.AnalysisService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class AnalysisController {

    private final AnalysisService analysisService;
    private final AnalysisManifestService manifestService;

    public AnalysisController(AnalysisService analysisService, AnalysisManifestService manifestService) {
        this.analysisService = analysisService;
        this.manifestService = manifestService;
    }

    /** Trigger phân tích source code (hoặc re-analyze). */
    @PostMapping("/analyze")
    public ResponseEntity<AnalysisResultDto> analyze(@PathVariable Long projectId) {
        AnalysisResultDto result = analysisService.analyze(projectId);
        return ResponseEntity.ok(result);
    }

    /** Lấy kết quả phân tích đã lưu. */
    @GetMapping("/analysis")
    public ResponseEntity<AnalysisResultDto> getAnalysis(@PathVariable Long projectId) {
        AnalysisResultDto result = analysisService.getAnalysisResult(projectId);
        return ResponseEntity.ok(result);
    }

    /** Xuất manifest deterministic dùng làm ground truth hoặc review thủ công. */
    @GetMapping("/analysis/manifest")
    public ResponseEntity<AnalysisManifestDto> exportManifest(@PathVariable Long projectId) {
        return ResponseEntity.ok(manifestService.exportManifest(projectId));
    }

    /** So sánh kết quả analysis với manifest ground truth. */
    @PostMapping("/analysis/manifest/validate")
    public ResponseEntity<AnalysisManifestValidationDto> validateManifest(
            @PathVariable Long projectId,
            @Valid @RequestBody AnalysisManifestInput expected) {
        return ResponseEntity.ok(manifestService.validateManifest(projectId, expected));
    }
}
