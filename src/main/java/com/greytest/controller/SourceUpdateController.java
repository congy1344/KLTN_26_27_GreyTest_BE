package com.greytest.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.GithubBranchUpdateRequest;
import com.greytest.dto.SourceUpdateDto;
import com.greytest.dto.SourceUpdateItemDto;
import com.greytest.dto.SourceUpdateItemPatchRequest;
import com.greytest.service.AuthService;
import com.greytest.service.IncrementalGenerationService;
import com.greytest.service.SourceUpdateService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/projects/{projectId}/source-updates")
public class SourceUpdateController {

    private final SourceUpdateService sourceUpdateService;
    private final IncrementalGenerationService incrementalGenerationService;
    private final AuthService authService;

    public SourceUpdateController(
            SourceUpdateService sourceUpdateService,
            IncrementalGenerationService incrementalGenerationService,
            AuthService authService) {
        this.sourceUpdateService = sourceUpdateService;
        this.incrementalGenerationService = incrementalGenerationService;
        this.authService = authService;
    }

    @PostMapping("/zip")
    public ResponseEntity<SourceUpdateDto> createZipUpdate(
            @PathVariable Long projectId,
            @RequestParam("file") MultipartFile file,
            @RequestHeader("Authorization") String authorization) {
        var user = authService.currentUser(authorization);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(sourceUpdateService.createZipUpdate(projectId, file, user));
    }

    @PostMapping("/github")
    public ResponseEntity<SourceUpdateDto> createGithubUpdate(
            @PathVariable Long projectId,
            @Valid @RequestBody GithubBranchUpdateRequest request,
            @RequestHeader("Authorization") String authorization) {
        var user = authService.currentUser(authorization);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(sourceUpdateService.createGithubUpdate(projectId, request.branch(), user));
    }

    @GetMapping
    public List<SourceUpdateDto> listUpdates(
            @PathVariable Long projectId,
            @RequestHeader("Authorization") String authorization) {
        var user = authService.currentUser(authorization);
        return sourceUpdateService.getUpdatesByProject(projectId, user);
    }

    @GetMapping("/{updateId}")
    public SourceUpdateDto getUpdate(
            @PathVariable Long projectId,
            @PathVariable Long updateId,
            @RequestHeader("Authorization") String authorization) {
        var user = authService.currentUser(authorization);
        return sourceUpdateService.getUpdate(projectId, updateId, user);
    }

    @PatchMapping("/{updateId}/items/{itemId}")
    public SourceUpdateItemDto patchItem(
            @PathVariable Long projectId,
            @PathVariable Long updateId,
            @PathVariable Long itemId,
            @RequestBody SourceUpdateItemPatchRequest request,
            @RequestHeader("Authorization") String authorization) {
        var user = authService.currentUser(authorization);
        return sourceUpdateService.patchItem(projectId, updateId, itemId, request, user);
    }

    @PostMapping("/{updateId}/analyze")
    public SourceUpdateDto analyzeUpdate(
            @PathVariable Long projectId,
            @PathVariable Long updateId,
            @RequestHeader("Authorization") String authorization) {
        var user = authService.currentUser(authorization);
        return sourceUpdateService.analyzeUpdate(projectId, updateId, user);
    }

    @PostMapping("/{updateId}/generate")
    public SourceUpdateDto generateIncremental(
            @PathVariable Long projectId,
            @PathVariable Long updateId,
            @RequestParam(name = "stage", defaultValue = "BUSINESS_RULE") GenerationProgressStage stage,
            @RequestHeader("Authorization") String authorization) {
        var user = authService.currentUser(authorization);
        return incrementalGenerationService.generateIncrementalStage(projectId, updateId, stage, user);
    }

    @PostMapping("/{updateId}/apply")
    public SourceUpdateDto applyUpdate(
            @PathVariable Long projectId,
            @PathVariable Long updateId,
            @RequestHeader("Authorization") String authorization) {
        var user = authService.currentUser(authorization);
        return sourceUpdateService.applyUpdate(projectId, updateId, user);
    }

    @PostMapping("/{updateId}/cancel")
    public ResponseEntity<Void> cancelUpdate(
            @PathVariable Long projectId,
            @PathVariable Long updateId,
            @RequestHeader("Authorization") String authorization) {
        var user = authService.currentUser(authorization);
        sourceUpdateService.cancelUpdate(projectId, updateId, user);
        return ResponseEntity.noContent().build();
    }
}
