package com.greytest.controller;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.greytest.dto.CreateTestCaseRequest;
import com.greytest.dto.TestCaseDto;
import com.greytest.dto.UpdateTestCaseRequest;
import com.greytest.service.AuthService;
import com.greytest.service.ProjectService;
import com.greytest.service.TestCaseService;
import jakarta.validation.Valid;

@RestController
public class TestCaseController {
 private final TestCaseService service; private final AuthService auth; private final ProjectService projects;
 public TestCaseController(TestCaseService service,AuthService auth,ProjectService projects){this.service=service;this.auth=auth;this.projects=projects;}
 @GetMapping("/api/projects/{projectId}/test-cases") public List<TestCaseDto> list(@PathVariable Long projectId,@RequestHeader("Authorization") String a){access(projectId,a);return service.list(projectId);}
 @PostMapping("/api/projects/{projectId}/test-cases/generate") public List<TestCaseDto> generate(@PathVariable Long projectId,@RequestParam(required=false) Long planId,@RequestHeader("Authorization") String a){access(projectId,a);return planId==null?service.generate(projectId):service.regenerate(projectId,planId);}
 @PostMapping("/api/projects/{projectId}/test-cases") @ResponseStatus(HttpStatus.CREATED) public TestCaseDto create(@PathVariable Long projectId,@RequestHeader("Authorization") String a,@Valid @RequestBody CreateTestCaseRequest r){access(projectId,a);return service.create(projectId,r);}
 @PostMapping("/api/projects/{projectId}/test-cases/approve") public List<TestCaseDto> approve(@PathVariable Long projectId,@RequestHeader("Authorization") String a){access(projectId,a);return service.approve(projectId);}
 @PutMapping("/api/test-cases/{caseId}") public TestCaseDto update(@PathVariable Long caseId,@RequestHeader("Authorization") String a,@Valid @RequestBody UpdateTestCaseRequest r){access(service.projectIdForCase(caseId),a);return service.update(caseId,r);}
 @DeleteMapping("/api/test-cases/{caseId}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable Long caseId,@RequestHeader("Authorization") String a){access(service.projectIdForCase(caseId),a);service.delete(caseId);}
 private void access(Long id,String a){projects.requireAccess(id,auth.currentUser(a));}
}
