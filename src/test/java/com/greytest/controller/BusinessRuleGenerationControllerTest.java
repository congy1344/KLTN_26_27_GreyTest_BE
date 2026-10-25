package com.greytest.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.greytest.dto.GenerationJobAcceptedDto;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.GenerationProgressStatus;
import com.greytest.entity.AuthUser;
import com.greytest.exception.GlobalExceptionHandler;
import com.greytest.service.AuthService;
import com.greytest.service.BusinessRuleService;
import com.greytest.service.GenerationJobService;
import com.greytest.service.ProjectService;

class BusinessRuleGenerationControllerTest {
    private final AuthService auth = mock(AuthService.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final BusinessRuleService rules = mock(BusinessRuleService.class);
    private final GenerationJobService jobs = mock(GenerationJobService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AuthUser actor = new AuthUser();
        actor.setId(42L);
        when(auth.currentUser("Bearer token")).thenReturn(actor);
        when(jobs.submit(eq(1L), eq(42L), eq(GenerationProgressStage.BUSINESS_RULE), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(3).run();
                    return new GenerationJobAcceptedDto(GenerationProgressStage.BUSINESS_RULE,
                            GenerationProgressStatus.QUEUED, "Queued");
                });
        mvc = MockMvcBuilders.standaloneSetup(new BusinessRuleController(rules, auth, projects, jobs))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void omittedResumeSafelyContinuesWithinSelectedService() throws Exception {
        mvc.perform(post("/api/projects/1/business-rules/generate")
                .param("servicePath", "backend/patient-service").header("Authorization", "Bearer token"))
                .andExpect(status().isAccepted());
        verify(rules).generate(1L, "backend/patient-service", true);
    }

    @Test
    void explicitRegenerationIsForwardedToTheWorker() throws Exception {
        mvc.perform(post("/api/projects/1/business-rules/generate").param("resume", "false")
                .param("servicePath", "backend/patient-service").header("Authorization", "Bearer token"))
                .andExpect(status().isAccepted());
        verify(rules).generate(1L, "backend/patient-service", false);
    }

    @Test
    void unauthenticatedRequestDoesNotScheduleWork() throws Exception {
        mvc.perform(post("/api/projects/1/business-rules/generate")).andExpect(status().isUnauthorized());
        verifyNoInteractions(jobs, rules);
    }
}
