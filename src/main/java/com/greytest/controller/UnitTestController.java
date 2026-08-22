package com.greytest.controller;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import com.greytest.dto.UnitTestDto;
import com.greytest.dto.GenerationJobAcceptedDto;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.service.AuthService;
import com.greytest.service.ProjectService;
import com.greytest.service.UnitTestService;
import com.greytest.service.GenerationJobService;

@RestController
public class UnitTestController {
 private final UnitTestService service; private final AuthService auth; private final ProjectService projects; private final GenerationJobService jobs;
 public UnitTestController(UnitTestService service,AuthService auth,ProjectService projects,GenerationJobService jobs){this.service=service;this.auth=auth;this.projects=projects;this.jobs=jobs;}
 @GetMapping("/api/projects/{projectId}/unit-tests") public List<UnitTestDto> list(@PathVariable Long projectId,@RequestHeader("Authorization") String a){access(projectId,a);return service.list(projectId);}
 @GetMapping("/api/projects/{projectId}/unit-tests/files") public List<com.greytest.dto.UnitTestFileDto> files(@PathVariable Long projectId,@RequestHeader("Authorization") String a){access(projectId,a);return service.listFiles(projectId);}
 @GetMapping("/api/projects/{projectId}/unit-tests/download") public org.springframework.http.ResponseEntity<byte[]> download(@PathVariable Long projectId,@RequestHeader("Authorization") String a){
  access(projectId,a);
  return org.springframework.http.ResponseEntity.ok()
    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"greytest-unit-tests.zip\"")
    .contentType(org.springframework.http.MediaType.parseMediaType("application/zip"))
    .body(service.zipFiles(projectId));
 }
 @PostMapping("/api/projects/{projectId}/unit-tests/generate") public org.springframework.http.ResponseEntity<GenerationJobAcceptedDto> generate(@PathVariable Long projectId,@RequestHeader("Authorization") String a){var actor=access(projectId,a);return org.springframework.http.ResponseEntity.accepted().body(jobs.submit(projectId,actor.getId(),GenerationProgressStage.UNIT_TEST,()->service.generate(projectId)));}
 private com.greytest.entity.AuthUser access(Long id,String a){var user=auth.currentUser(a);projects.requireAccess(id,user);return user;}
}
