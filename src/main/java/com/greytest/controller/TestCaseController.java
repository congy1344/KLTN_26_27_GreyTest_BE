package com.greytest.controller;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.greytest.dto.CreateTestCaseRequest;
import com.greytest.dto.TestCaseDto;
import com.greytest.dto.UpdateTestCaseRequest;
import com.greytest.dto.GenerationJobAcceptedDto;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.service.AuthService;
import com.greytest.service.ProjectService;
import com.greytest.service.TestCaseService;
import com.greytest.service.GenerationJobService;
import jakarta.validation.Valid;

@RestController
public class TestCaseController {
 private final TestCaseService service; private final AuthService auth; private final ProjectService projects; private final GenerationJobService jobs;
 public TestCaseController(TestCaseService service,AuthService auth,ProjectService projects,GenerationJobService jobs){this.service=service;this.auth=auth;this.projects=projects;this.jobs=jobs;}
 @GetMapping("/api/projects/{projectId}/test-cases") public List<TestCaseDto> list(@PathVariable Long projectId,@RequestParam(required=false) String servicePath,@RequestHeader("Authorization") String a){access(projectId,a);return service.list(projectId,servicePath);}
 @PostMapping("/api/projects/{projectId}/test-cases/generate") public org.springframework.http.ResponseEntity<GenerationJobAcceptedDto> generate(@PathVariable Long projectId,@RequestParam(required=false) Long planId,@RequestParam(required=false) String servicePath,@RequestHeader("Authorization") String a){var actor=access(projectId,a);return org.springframework.http.ResponseEntity.accepted().body(jobs.submit(projectId,actor.getId(),GenerationProgressStage.TEST_CASE,()->{if(planId==null)service.generate(projectId,servicePath);else service.regenerate(projectId,servicePath,planId);}));}
 @PostMapping("/api/projects/{projectId}/test-cases") @ResponseStatus(HttpStatus.CREATED) public TestCaseDto create(@PathVariable Long projectId,@RequestParam(required=false) String servicePath,@RequestHeader("Authorization") String a,@Valid @RequestBody CreateTestCaseRequest r){access(projectId,a);return jobs.executeMutation(projectId,()->service.create(projectId,servicePath,r));}
 @PostMapping("/api/projects/{projectId}/test-cases/approve") public List<TestCaseDto> approve(@PathVariable Long projectId,@RequestParam(required=false) String servicePath,@RequestHeader("Authorization") String a){access(projectId,a);return jobs.executeMutation(projectId,()->service.approve(projectId,servicePath));} @PutMapping("/api/test-cases/{caseId}") public TestCaseDto update(@PathVariable Long caseId,@RequestHeader("Authorization") String a,@Valid @RequestBody UpdateTestCaseRequest r){Long projectId=service.projectIdForCase(caseId);access(projectId,a);return jobs.executeMutation(projectId,()->service.update(caseId,r));}
 @DeleteMapping("/api/test-cases/{caseId}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable Long caseId,@RequestHeader("Authorization") String a){Long projectId=service.projectIdForCase(caseId);access(projectId,a);jobs.executeMutation(projectId,()->service.delete(caseId));}
 private com.greytest.entity.AuthUser access(Long id,String a){var user=auth.currentUser(a);projects.requireAccess(id,user);return user;}
}
