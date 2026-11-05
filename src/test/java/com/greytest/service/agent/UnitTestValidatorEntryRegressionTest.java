package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.greytest.dto.agent.GenerationContextDtos.*;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedUnitTestDto;

class UnitTestValidatorEntryRegressionTest {
    @Test void recognizesMockitoSpyButDoesNotTrustAShadowAnnotation() throws Exception {
        String code = "import org.mockito.*; class Check { @Spy @InjectMocks Service sut; "
                + "void check() { org.mockito.Mockito.when(sut.read()).thenReturn(1); org.mockito.Mockito.verify(sut).read(); } }";
        assertThat(spyProblems(code)).isEmpty();
        assertThat(spyProblems(code.replace("@Spy ", ""))).isNotEmpty();
        assertThat(spyProblems(code.replace("class Check {", "class Check { @interface Spy {}"))).isNotEmpty();
    }

    @Test void checksFieldsAgainstThePublicEntryPointAndStillRejectsAMissingSetter() throws Exception {
        String helper = "private void level(Item item) { item.setLevel(1); }";
        var target = new MethodContextDto(1L,"sample.Service","level","void",List.of(),List.of(),"PRIVATE",helper,1,1,List.of(),List.of(),List.of());
        String source = "package sample; class Service { public void create(Item item) { item.setTime(new Object()); level(item); } " + helper + " }";
        String code = "package sample; class Check { Service sut; @org.junit.jupiter.api.Test void check() { Item child=new Item(); sut.create(child); org.junit.jupiter.api.Assertions.assertNotNull(child.getTime()); } }";
        assertThat(fieldProblems(target,source,code)).isEmpty();
        assertThat(fieldProblems(target,source,code.replace("assertNotNull(child.getTime())", "assertEquals(1, child.getLevel())"))).isEmpty();
        assertThat(fieldProblems(target,source.replace("item.setTime(new Object());", ""),code)).isNotEmpty();
    }

    private List<String> spyProblems(String code) throws Exception {
        var target=new MethodContextDto(1L,"sample.Service","calculate","int",List.of(),List.of(),"PUBLIC","int calculate(){return 1;}",1,1,List.of(),List.of(),List.of());
        return spyProblems(code,target,List.of());
    }

    private List<String> spyProblems(String code, MethodContextDto target, List<ClassContextDto> classes) throws Exception {
        var method = GeneratedUnitTestSemanticValidator.class.getDeclaredMethod("validateInjectMocksStubbing",GeneratedUnitTestDto.class,MethodContextDto.class,List.class,List.class);
        method.setAccessible(true);
        var errors = new ArrayList<String>(); method.invoke(null,unit(code),target,classes,errors);return errors;
    }

    @Test void permitsOverloadedHelpersAndDoesNotClassifyCustomWhenAsMockito() throws Exception {
        var target=new MethodContextDto(1L,"sample.Service","calculate","int",List.of(),List.of(),"PUBLIC","int calculate(int value){return calculate()+value;}",1,1,List.of(),List.of(),List.of());
        String source="package sample; class Service { int calculate(){return 1;} int calculate(int value){return calculate()+value;} }";
        var classes=List.of(new ClassContextDto(1L,"sample","Service","sample.Service","SERVICE","Service.java",source,List.of(),List.of(target)));
        String prefix="import org.mockito.*; class Check { @Spy @InjectMocks Service sut; void check(){ ";
        assertThat(spyProblems(prefix+"org.mockito.Mockito.doReturn(7).when(sut).calculate(); sut.calculate(3); } }",target,classes)).isEmpty();
        assertThat(spyProblems(prefix+"org.mockito.Mockito.when(sut.calculate()).thenReturn(7); sut.calculate(3); } }",target,classes)).isEmpty();
        assertThat(spyProblems(prefix+"org.mockito.Mockito.doReturn(7).when(sut).calculate(3); } }",target,classes)).isNotEmpty();
        assertThat(spyProblems(prefix+"custom.when(sut.calculate(3)); } }",target,classes)).isEmpty();
        assertThat(spyProblems(prefix+"custom.doReturn(7).when(sut).calculate(3); } }",target,classes)).isEmpty();
        var ambiguous=List.of(new ClassContextDto(1L,"sample","Service","sample.Service","SERVICE","Service.java",source.replace("int calculate(){return 1;}","int calculate(){return 1;} int calculate(String value){return 7;}"),List.of(),List.of(target)));
        assertThat(spyProblems(prefix+"org.mockito.Mockito.doReturn(7).when(sut).calculate(\"helper\"); } }",target,ambiguous)).isEmpty();
    }

    @Test void doesNotAllowStubbingTheActualMethodUnderTestOnASpy() throws Exception {
        String prefix="import org.mockito.*; class Check { @Spy @InjectMocks Service sut; void check(){ ";
        assertThat(spyProblems(prefix+"org.mockito.Mockito.when(sut.calculate()).thenReturn(10); } }")).isNotEmpty();
        assertThat(spyProblems(prefix+"org.mockito.Mockito.doReturn(10).when(sut).calculate(); } }")).isNotEmpty();
        assertThat(spyProblems(prefix+"org.mockito.Mockito.when(this.sut.calculate()).thenReturn(10); } }")).isNotEmpty();
        assertThat(spyProblems(prefix+"org.mockito.Mockito.doReturn(10).when(this.sut).calculate(); } }")).isNotEmpty();
        assertThat(spyProblems("import org.mockito.*; class Check { @Spy @InjectMocks Service sut; @Spy Repository repository; "
                + "void check(){ org.mockito.Mockito.doReturn(10).when(repository).calculate(); } }")).isEmpty();
    }

    @Test void rejectsDoStyleStubbingOnPlainInjectMocksIncludingInheritedHelpers() throws Exception {
        String prefix="import org.mockito.*; class Check { @InjectMocks Service sut; void check(){ ";
        for (String factory : List.of("doReturn(10)", "doThrow(new RuntimeException())", "doAnswer(i -> 10)", "doNothing()")) {
            String code=prefix+"org.mockito.Mockito."+factory+".when(sut).read(); } }";
            assertThat(spyProblems(code)).isNotEmpty();
            assertThat(spyProblems(code.replace("@InjectMocks", "@Spy @InjectMocks"))).isEmpty();
        }
        assertThat(spyProblems(prefix+"Service sut=org.mockito.Mockito.spy(new Service()); org.mockito.Mockito.doReturn(10).when(sut).read(); } }")).isEmpty();
        String lifecycle="import org.mockito.*; class Check { @InjectMocks Service sut; "
                + "@org.junit.jupiter.api.BeforeEach void setup(){sut=org.mockito.Mockito.spy(sut);} void check(){";
        assertThat(spyProblems(lifecycle+"org.mockito.Mockito.doReturn(10).when(sut).read(); } }")).isEmpty();
        assertThat(spyProblems(lifecycle+"org.mockito.Mockito.doReturn(10).when(sut).calculate(); } }")).isNotEmpty();
        assertThat(spyProblems(lifecycle+"org.mockito.Mockito.doReturn(10).when(this.sut).calculate(); } }")).isNotEmpty();
        assertThat(spyProblems(lifecycle+"org.mockito.Mockito.when(this.sut.calculate()).thenReturn(10); } }")).isNotEmpty();
        String initialized="import org.mockito.*; class Check { @InjectMocks Service sut=org.mockito.Mockito.spy(new Service()); void check(){";
        assertThat(spyProblems(initialized+"org.mockito.Mockito.doReturn(10).when(sut).read(); } }")).isEmpty();
        assertThat(spyProblems(initialized+"org.mockito.Mockito.when(sut.calculate()).thenReturn(10); } }")).isNotEmpty();
    }

    private List<String> fieldProblems(MethodContextDto target,String source,String code) throws Exception {
        var classes=List.of(new ClassContextDto(1L,"sample","Service","sample.Service","SERVICE","Service.java",source,List.of(),List.of(target)));
        var method=GeneratedUnitTestSemanticValidator.class.getDeclaredMethod("validateFixtureAssertionConsistency",GeneratedUnitTestDto.class,MethodContextDto.class,List.class,List.class);
        method.setAccessible(true);
        var errors=new ArrayList<String>();method.invoke(null,unit(code),target,classes,errors);return errors;
    }

    private GeneratedUnitTestDto unit(String code) {return new GeneratedUnitTestDto(1L,"Check","check","sample","NEW_TEST",code);}
}
