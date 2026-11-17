package com.greytest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.greytest.entity.AuthUser;
import com.greytest.service.AuthService;
import com.greytest.service.ProjectService;
import com.greytest.service.coverage.SandboxTestRunService;

class SandboxTestRunControllerTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void manualEndpointKeepsItsExistingRunPathAndUsesTheAuthenticatedRepairActor(boolean repair) {
        var sandbox=mock(SandboxTestRunService.class);
        var auth=mock(AuthService.class);
        var projects=mock(ProjectService.class);
        var user=new AuthUser(); user.setId(7L);
        when(auth.currentUser("token")).thenReturn(user);
        var state=new SandboxTestRunService.RunState(SandboxTestRunService.Status.RUNNING,"Đang chạy.",0,0,"");
        when(sandbox.start(1L,"shop",repair?7L:null)).thenReturn(state);
        var controller=new SandboxTestRunController(sandbox,auth,projects);
        assertThat(controller.start(1L,"shop",repair,"token").getBody()).isEqualTo(state);
        verify(projects).requireAccess(1L,user);
        verify(sandbox).start(1L,"shop",repair?7L:null);
        verifyNoMoreInteractions(sandbox);
    }
}
