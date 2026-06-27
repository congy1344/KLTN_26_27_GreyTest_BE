package com.greytest.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import com.greytest.dto.AnalysisResultDto;
import com.greytest.dto.AnalysisManifestDto;
import com.greytest.dto.AnalysisManifestValidationDto;
import com.greytest.dto.ManifestCategoryDiffDto;
import com.greytest.exception.InvalidProjectStatusException;
import com.greytest.service.analysis.AnalysisManifestService;
import com.greytest.service.analysis.AnalysisService;

@WebMvcTest(AnalysisController.class)
class AnalysisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AnalysisService analysisService;

    @MockBean
    private AnalysisManifestService manifestService;

    @Test
    void analyzeReturnsAnalysisResult() throws Exception {
        when(analysisService.analyze(1L)).thenReturn(result());

        mockMvc.perform(post("/api/projects/1/analyze"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(1))
                .andExpect(jsonPath("$.status").value("ANALYZED"))
                .andExpect(jsonPath("$.existingTestFiles").value(0));
    }

    @Test
    void getAnalysisReturnsStoredResult() throws Exception {
        when(analysisService.getAnalysisResult(1L)).thenReturn(result());

        mockMvc.perform(get("/api/projects/1/analysis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClasses").value(0));
    }

    @Test
    void analyzeRejectsInvalidProjectStatus() throws Exception {
        when(analysisService.analyze(1L))
                .thenThrow(new InvalidProjectStatusException("Không thể phân tích"));

        mockMvc.perform(post("/api/projects/1/analyze"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
    }

    @Test
    void exportsAnalysisManifest() throws Exception {
        when(manifestService.exportManifest(1L)).thenReturn(new AnalysisManifestDto(
                1L, "demo", "1.1", List.of("demo.User"), List.of(), List.of(),
                List.of(), List.of(), List.of()));

        mockMvc.perform(get("/api/projects/1/analysis/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manifestVersion").value("1.1"))
                .andExpect(jsonPath("$.classes[0]").value("demo.User"));
    }

    @Test
    void validatesManifestAndReportsDifferences() throws Exception {
        ManifestCategoryDiffDto mismatch = new ManifestCategoryDiffDto(
                1, 1, false, List.of("demo.Expected"), List.of("demo.Actual"));
        ManifestCategoryDiffDto match = new ManifestCategoryDiffDto(0, 0, true, List.of(), List.of());
        when(manifestService.validateManifest(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.any())).thenReturn(new AnalysisManifestValidationDto(
                        1L, "demo", false, mismatch, match, match, match, match, match));

        mockMvc.perform(post("/api/projects/1/analysis/manifest/validate")
                        .contentType("application/json")
                        .content("""
                                {
                                  "classes": ["demo.Expected"],
                                  "methods": [],
                                  "endpoints": [],
                                  "annotations": [],
                                  "controllerServiceRelations": [],
                                  "serviceRepositoryRelations": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exactMatch").value(false))
                .andExpect(jsonPath("$.classes.missing[0]").value("demo.Expected"))
                .andExpect(jsonPath("$.classes.unexpected[0]").value("demo.Actual"));
    }

    private AnalysisResultDto result() {
        return new AnalysisResultDto(1L, "demo", "ANALYZED", 0, 0, 0, 0, 0, 0,
                0, 0, 0, List.of(),
                List.of(), List.of(), List.of());
    }
}
