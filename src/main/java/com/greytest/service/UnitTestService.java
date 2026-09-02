package com.greytest.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.greytest.dto.UnitTestDto;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedUnitTestDto;
import com.greytest.entity.Project;
import com.greytest.entity.TestCase;
import com.greytest.entity.TestPlan;
import com.greytest.entity.UnitTest;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.exception.InvalidProjectStatusException;
import com.greytest.exception.ProjectNotFoundException;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.repository.UnitTestRepository;
import com.greytest.service.agent.AIAgentService;
import com.greytest.service.agent.GenerationContextBuilder;
import com.greytest.service.agent.LlmResponseException;

/** Sinh va luu unit test JUnit/Mockito tu test case da duyet. */
@Service
public class UnitTestService {
    private static final int MAX_UNIT_TEST_RECOVERY_CALLS = 4;
    private final UnitTestRepository units; private final TestCaseRepository cases; private final TestPlanRepository plans; private final ProjectRepository projects; private final AIAgentService ai; private final UnitTestFileService files; private final TransactionTemplate transactions; private final GenerationProgressService generationProgress;
    private ServiceScopeResolver scopeResolver; private ServicePipelineStatusService scopedArtifacts;
    public UnitTestService(UnitTestRepository units, TestCaseRepository cases, TestPlanRepository plans, ProjectRepository projects, AIAgentService ai, UnitTestFileService files, PlatformTransactionManager transactionManager, GenerationProgressService generationProgress){this.units=units;this.cases=cases;this.plans=plans;this.projects=projects;this.ai=ai;this.files=files;this.transactions=new TransactionTemplate(transactionManager);this.generationProgress=generationProgress;}
    @org.springframework.beans.factory.annotation.Autowired
    public UnitTestService(UnitTestRepository units,TestCaseRepository cases,TestPlanRepository plans,ProjectRepository projects,AIAgentService ai,UnitTestFileService files,PlatformTransactionManager transactionManager,GenerationProgressService generationProgress,ServiceScopeResolver scopeResolver,ServicePipelineStatusService scopedArtifacts){
        this(units,cases,plans,projects,ai,files,transactionManager,generationProgress);
        this.scopeResolver=scopeResolver;
        this.scopedArtifacts=scopedArtifacts;
    }

    @Transactional(readOnly=true) public List<UnitTestDto> list(Long projectId){ensure(projectId); return cases.findAll().stream().filter(c->approvedCase(c,projectId)).map(c->units.findByTestCaseId(c.getId())).filter(java.util.Objects::nonNull).map(this::dto).toList();}
    @Transactional(readOnly=true) public List<UnitTestDto> list(Long projectId,String servicePath){ensure(projectId);return approvedCases(projectId,scopeResolver.resolve(projectId,servicePath)).stream().map(c->units.findByTestCaseId(c.getId())).filter(java.util.Objects::nonNull).map(this::dto).toList();}
    public List<UnitTestDto> generate(Long projectId){return generate(projectId,(ServiceScopeResolver.ServiceScope)null);}
    public List<UnitTestDto> generate(Long projectId,String servicePath){return generate(projectId,scopeResolver.resolve(projectId,servicePath));}
    private List<UnitTestDto> generate(Long projectId,ServiceScopeResolver.ServiceScope scope){
        Project project=ensure(projectId);
        if(scope==null)ensureCanGenerate(project);
        var approved=scope==null?approvedCases(projectId):approvedCases(projectId,scope);
        if(approved.isEmpty()) throw new LlmResponseException("Khong co Test Case da approve de sinh Unit Test.");
        startProgress(projectId, approved, "Bắt đầu sinh Unit Test từ Test Case đã approve.");
        try {
        var generated=generateBatches(projectId,approved);
        var expectedIds=approved.stream().map(TestCase::getId).collect(java.util.stream.Collectors.toSet());
        List<UnitTestDto> saved=transactions.execute(status->persistGenerated(projectId,scope,expectedIds,generated));
        generationProgress.completeAfterCommit(projectId, GenerationProgressStage.UNIT_TEST,
                "Hoàn tất: đã lưu " + (saved == null ? 0 : saved.size()) + " Unit Test.");
        return saved;
        } catch(RuntimeException exception){
            generationProgress.fail(projectId, GenerationProgressStage.UNIT_TEST,
                    "Sinh Unit Test thất bại; xem thông báo lỗi để biết chi tiết.");
            throw exception;
        }
    }
    /** Sinh Unit Test chỉ cho case vòng mới, giữ nguyên toàn bộ output các vòng trước. */
    public List<UnitTestDto> generateSupplemental(Long projectId,String servicePath,List<Long> caseIds){
        var allowed=approvedCases(projectId,scopeResolver.resolve(projectId,servicePath)).stream().map(TestCase::getId).collect(java.util.stream.Collectors.toSet());
        if(!allowed.containsAll(caseIds)){
            throw new InvalidProjectStatusException("Test Case bổ sung không thuộc servicePath đã chọn.");
        }
        return generateSupplemental(projectId,caseIds);
    }

    public List<UnitTestDto> generateSupplemental(Long projectId,List<Long> caseIds){
        Project p=ensure(projectId);
        ensureCanGenerate(p);
        var targetIds=new java.util.HashSet<>(caseIds);
        var target=caseIds.stream().map(id->cases.findById(id).orElse(null)).filter(java.util.Objects::nonNull).filter(c->approvedCase(c,projectId)).toList();
        if(target.size()!=targetIds.size()) throw new InvalidProjectStatusException("Test Case bổ sung không hợp lệ.");
        startProgress(projectId, target, "Bắt đầu sinh Unit Test bổ sung cho coverage gap.");
        try {
        var generated=generateBatches(projectId,target);
        List<UnitTestDto> saved=transactions.execute(status->persistSupplemental(projectId,targetIds,generated));
        generationProgress.completeAfterCommit(projectId, GenerationProgressStage.UNIT_TEST,
                "Hoàn tất: đã lưu " + (saved == null ? 0 : saved.size()) + " Unit Test bổ sung.");
        return saved;
        } catch(RuntimeException exception){
            generationProgress.fail(projectId, GenerationProgressStage.UNIT_TEST,
                    "Sinh Unit Test bổ sung thất bại; xem thông báo lỗi để biết chi tiết.");
            throw exception;
        }
    }
    private List<GeneratedUnitTestDto> generateBatches(Long projectId,List<TestCase> target){
        List<GeneratedUnitTestDto> generated=new ArrayList<>();
        int batchNumber=0;
        int totalBatches=batchCount(target.size());
        for(int start=0;start<target.size();start+=GenerationContextBuilder.MAX_UNIT_TEST_CASES){
            var batch=target.subList(start,Math.min(start+GenerationContextBuilder.MAX_UNIT_TEST_CASES,target.size()));
            Set<Long> ids=batch.stream().map(TestCase::getId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            var valid=generateBatchWithRecovery(projectId,ids,batchNumber+1,totalBatches,
                    new RecoveryBudget(MAX_UNIT_TEST_RECOVERY_CALLS),false);
            generated.addAll(valid);
            batchNumber++;
            generationProgress.advance(projectId, GenerationProgressStage.UNIT_TEST,
                    "Batch " + batchNumber + "/" + totalBatches + ": đã kiểm tra "
                            + valid.size() + " Unit Test hợp lệ.");
        }
        return uniqueMethodNames(generated,Set.of());
    }
    private List<GeneratedUnitTestDto> generateBatchWithRecovery(
            Long projectId,Set<Long> requestedIds,int batchNumber,int totalBatches,
            RecoveryBudget recoveryBudget,boolean recoveryCall){
        if(recoveryCall&&!recoveryBudget.tryConsume()){
            throw new LlmResponseException("Không thể sinh Unit Test hợp lệ cho Test Case ID " + requestedIds
                    + " vì đã hết giới hạn " + MAX_UNIT_TEST_RECOVERY_CALLS
                    + " lượt phục hồi sau khi tự chia batch.");
        }
        var response=ai.generateUnitTests(projectId,requestedIds);
        Map<Long,List<GeneratedUnitTestDto>> candidates=response.unitTests().stream()
                .filter(java.util.Objects::nonNull)
                .filter(test->requestedIds.contains(test.caseId()))
                .filter(test->valid(test,projectId)&&validJavaSource(test))
                .collect(java.util.stream.Collectors.groupingBy(
                        GeneratedUnitTestDto::caseId,LinkedHashMap::new,java.util.stream.Collectors.toList()));
        Map<Long,GeneratedUnitTestDto> accepted=new LinkedHashMap<>();
        requestedIds.forEach(id->{
            List<GeneratedUnitTestDto> matches=candidates.getOrDefault(id,List.of());
            if(matches.size()==1) accepted.put(id,matches.get(0));
        });
        List<Long> unresolved=requestedIds.stream().filter(id->!accepted.containsKey(id)).toList();
        if(unresolved.isEmpty()) return new ArrayList<>(accepted.values());
        if(requestedIds.size()==1){
            if(!recoveryCall){
                generationProgress.log(projectId,GenerationProgressStage.UNIT_TEST,
                        "Batch " + batchNumber + "/" + totalBatches + " có Unit Test thiếu hoặc hỏng cho Test Case ID "
                                + unresolved + ". Hệ thống đang thử lại riêng case này.");
                return generateBatchWithRecovery(projectId,requestedIds,batchNumber,totalBatches,recoveryBudget,true);
            }
            Long caseId=requestedIds.iterator().next();
            throw new LlmResponseException("Không thể sinh Unit Test hợp lệ cho Test Case ID " + caseId
                    + " sau khi đã tự chia batch và thử lại.");
        }
        generationProgress.log(projectId,GenerationProgressStage.UNIT_TEST,
                "Batch " + batchNumber + "/" + totalBatches + " thiếu hoặc hỏng Unit Test cho Test Case ID "
                        + unresolved + ". Hệ thống đang tự chia nhóm và chỉ thử lại các case này.");
        int middle=Math.max(1,unresolved.size()/2);
        List<List<Long>> recoveryGroups=unresolved.size()==1
                ? List.of(unresolved)
                : List.of(unresolved.subList(0,middle),unresolved.subList(middle,unresolved.size()));
        for(List<Long> group:recoveryGroups){
            Set<Long> groupIds=new LinkedHashSet<>(group);
            generateBatchWithRecovery(projectId,groupIds,batchNumber,totalBatches,recoveryBudget,true)
                    .forEach(test->accepted.put(test.caseId(),test));
        }
        return requestedIds.stream().map(accepted::get).toList();
    }
    private boolean validJavaSource(GeneratedUnitTestDto test){
        if(test.sourceCode()==null||test.sourceCode().isBlank()) return false;
        try{
            var configuration=new com.github.javaparser.ParserConfiguration()
                    .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_21);
            var result=new com.github.javaparser.JavaParser(configuration).parse(test.sourceCode());
            var unit=result.getResult().filter(ignored->result.isSuccessful()).orElse(null);
            if(unit==null) return false;
            return unit.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).stream()
                    .filter(type->test.testClassName().equals(type.getNameAsString()))
                    .flatMap(type->type.getMethodsByName(test.testMethodName()).stream())
                    .anyMatch(method->method.getAnnotations().stream()
                            .anyMatch(annotation->annotation.getNameAsString().equals("Test")
                                    || annotation.getNameAsString().endsWith(".Test")));
        }catch(RuntimeException exception){
            return false;
        }
    }
    private static final class RecoveryBudget{
        private int remainingCalls;
        private RecoveryBudget(int remainingCalls){this.remainingCalls=remainingCalls;}
        private boolean tryConsume(){
            if(remainingCalls<=0) return false;
            remainingCalls--;
            return true;
        }
    }
    private void startProgress(Long projectId,List<TestCase> target,String message){
        generationProgress.start(projectId,GenerationProgressStage.UNIT_TEST,batchCount(target.size())+1,
                message+" Tổng cộng "+target.size()+" Test Case.");
    }
    private int batchCount(int itemCount){return (itemCount+GenerationContextBuilder.MAX_UNIT_TEST_CASES-1)/GenerationContextBuilder.MAX_UNIT_TEST_CASES;}
    private List<GeneratedUnitTestDto> uniqueMethodNames(List<GeneratedUnitTestDto> generated,Set<String> existingKeys){
        Set<String> used=new java.util.HashSet<>(existingKeys);
        List<GeneratedUnitTestDto> unique=new ArrayList<>();
        for(GeneratedUnitTestDto test:generated){
            String name=test.testMethodName();
            String key=methodKey(test);
            if(!used.add(key)){
                name=uniqueName(test,used);
                key=test.packageName()+"\n"+test.testClassName()+"\n"+name;
                used.add(key);
            }
            unique.add(new GeneratedUnitTestDto(test.caseId(),test.testClassName(),name,test.packageName(),test.generationType(),renameMethod(test.sourceCode(),test.testClassName(),test.testMethodName(),name)));
        }
        return unique;
    }
    private String uniqueName(GeneratedUnitTestDto test,Set<String> used){
        String base=test.testMethodName()+"Case"+test.caseId();
        String name=base;
        int suffix=2;
        while(used.contains(test.packageName()+"\n"+test.testClassName()+"\n"+name)) name=base+"_"+suffix++;
        return name;
    }
    private String renameMethod(String source,String testClassName,String oldName,String newName){
        if(oldName.equals(newName)) return source;
        try{
            var result=new com.github.javaparser.JavaParser().parse(source);
            var unit=result.getResult().filter(ignored->result.isSuccessful()).orElse(null);
            if(unit!=null){
                var targetType=unit.getTypes().stream()
                        .filter(type->testClassName.equals(type.getNameAsString())).findFirst().orElse(null);
                if(targetType!=null){
                    var method=junitTestMethods(unit,importsJunitTest(unit)).stream()
                            .filter(candidate->targetType.getMethods().contains(candidate))
                            .filter(candidate->oldName.equals(candidate.getNameAsString()))
                            .findFirst().orElse(null);
                    if(method!=null){method.setName(newName);return unit.toString();}
                }
            }
        }catch(Exception ignored){
            // Nếu source AI chưa parse được, vẫn đổi tên bằng regex tối thiểu để user có file sửa tiếp.
        }
        return source.replaceFirst("(?<![A-Za-z0-9_$])"+java.util.regex.Pattern.quote(oldName)+"\\s*\\(",java.util.regex.Matcher.quoteReplacement(newName+"("));
    }
    private String methodKey(GeneratedUnitTestDto test){return test.packageName()+"\n"+test.testClassName()+"\n"+test.testMethodName();}
    private String methodKey(UnitTest test){return test.getPackageName()+"\n"+test.getTestClassName()+"\n"+test.getTestMethodName();}
    private List<UnitTestDto> persistGenerated(Long projectId,ServiceScopeResolver.ServiceScope scope,Set<Long> expectedIds,List<GeneratedUnitTestDto> generated){
        Project p=lockedProject(projectId);
        if(scope==null)ensureCanGenerate(p);
        var approved=scope==null?approvedCases(projectId):approvedCases(projectId,scope);
        var currentIds=approved.stream().map(TestCase::getId).collect(java.util.stream.Collectors.toSet());
        if(!currentIds.equals(expectedIds)) throw new InvalidProjectStatusException("Test Case da thay doi trong luc sinh Unit Test. Hay thu lai.");
        deleteOldUnitTests(approved);
        var saved=units.saveAll(generated.stream().map(this::from).toList());
        p.setStatus(ProjectStatus.TEST_GENERATED); projects.save(p);
        return saved.stream().map(this::dto).toList();
    }
    private List<UnitTestDto> persistSupplemental(Long projectId,Set<Long> expectedIds,List<GeneratedUnitTestDto> generated){
        Project p=lockedProject(projectId);
        ensureCanGenerate(p);
        var current=expectedIds.stream().map(id->cases.findById(id).orElse(null)).filter(java.util.Objects::nonNull).filter(c->approvedCase(c,projectId)).toList();
        if(current.size()!=expectedIds.size()) throw new InvalidProjectStatusException("Test Case bổ sung đã thay đổi trong lúc sinh Unit Test. Hãy thử lại.");
        var existingKeys=approvedCases(projectId).stream().filter(testCase->!expectedIds.contains(testCase.getId()))
                .map(testCase->units.findByTestCaseId(testCase.getId())).filter(java.util.Objects::nonNull)
                .map(this::methodKey).collect(java.util.stream.Collectors.toSet());
        generated=uniqueMethodNames(generated,existingKeys);
        var saved=units.saveAll(generated.stream().map(x->from(x,"SUPPLEMENT_EXISTING_TEST")).toList());
        p.setStatus(ProjectStatus.TEST_GENERATED); projects.save(p);
        return saved.stream().map(this::dto).toList();
    }
    private void ensureCanGenerate(Project p){if(p.getStatus()!=ProjectStatus.CASE_APPROVED) throw new InvalidProjectStatusException("Chi sinh Unit Test sau khi Test Case da approve.");}
    private List<TestCase> approvedCases(Long projectId){return cases.findAll().stream().filter(c->approvedCase(c,projectId)).sorted(java.util.Comparator.comparing(TestCase::getId)).toList();}
    private Project lockedProject(Long id){return projects.findByIdForUpdate(id).orElseThrow(()->new ProjectNotFoundException(id));}
    private List<TestCase> approvedCases(Long projectId,ServiceScopeResolver.ServiceScope scope){return scopedArtifacts.cases(projectId,scope).stream().filter(c->c.getStatus()==ReviewStatus.APPROVED).sorted(java.util.Comparator.comparing(TestCase::getId)).toList();}
    /** Xóa unit test của vòng trước để sinh lại không bị nhân đôi record. */
    private void deleteOldUnitTests(List<TestCase> approved){ approved.stream().map(c->units.findByTestCaseId(c.getId())).filter(java.util.Objects::nonNull).forEach(units::delete); units.flush(); }
    private boolean valid(GeneratedUnitTestDto x,Long projectId){if(x==null)return false; TestCase c=cases.findById(x.caseId()).orElse(null); return c!=null&&approvedCase(c,projectId)&&x.sourceCode()!=null&&!x.sourceCode().isBlank()&&validIdentifier(x.testClassName())&&validIdentifier(x.testMethodName())&&validPackage(x.packageName())&&containsExactlyOneRequestedTest(x)&&hasInitializedReferencedFixtures(x);}
    /** Chuẩn hóa source AI trả về cả class nhiều test thành đúng test case đang xử lý. */
    private GeneratedUnitTestDto normalizeCandidate(GeneratedUnitTestDto candidate){
        if(candidate==null||candidate.sourceCode()==null||candidate.sourceCode().isBlank())return candidate;
        try{
            var result=new com.github.javaparser.JavaParser().parse(candidate.sourceCode());
            var unit=result.getResult().filter(ignored->result.isSuccessful()).orElse(null);
            if(unit==null)return candidate;
            var targetType=unit.getTypes().stream()
                    .filter(type->candidate.testClassName().equals(type.getNameAsString())).findFirst().orElse(null);
            if(targetType==null)return candidate;
            var testMethods=junitTestMethods(unit,importsJunitTest(unit));
            if(testMethods.size()<=1)return candidate;
            var requested=testMethods.stream()
                    .filter(method->candidate.testMethodName().equals(method.getNameAsString()))
                    .findFirst().orElse(null);
            if(requested==null||!targetType.getMethods().contains(requested))return candidate;
            testMethods.stream().filter(method->method!=requested).forEach(method->method.remove());
            return new GeneratedUnitTestDto(candidate.caseId(),candidate.testClassName(),candidate.testMethodName(),
                    candidate.packageName(),candidate.generationType(),unit.toString());
        }catch(Exception ignored){
            return candidate;
        }
    }

    /**
     * Từ chối test dùng fixture object chưa được khởi tạo, tránh NPE runtime sau khi user dán source.
     */
    private boolean hasInitializedReferencedFixtures(GeneratedUnitTestDto test) {
        try {
            var parsed = new com.github.javaparser.JavaParser().parse(test.sourceCode());
            var unit = parsed.getResult().filter(ignored -> parsed.isSuccessful()).orElse(null);
            if (unit == null) return false;
            var targetType = unit.getTypes().stream()
                    .filter(type -> test.testClassName().equals(type.getNameAsString()))
                    .findFirst().orElse(null);
            if (targetType == null) return false;
            var testMethod = junitTestMethods(unit, importsJunitTest(unit)).stream()
                    .filter(method -> targetType.getMethods().contains(method))
                    .filter(method -> test.testMethodName().equals(method.getNameAsString()))
                    .findFirst().orElse(null);
            if (testMethod == null) return false;

            Set<String> candidates = new java.util.HashSet<>();
            for (com.github.javaparser.ast.body.FieldDeclaration field : targetType.getFields()) {
                if (isManagedFixture(field)) continue;
                field.getVariables().stream()
                        .filter(variable -> !variable.getType().isPrimitiveType())
                        .filter(variable -> variable.getInitializer().map(initializer -> initializer.isNullLiteralExpr()).orElse(true))
                        .map(com.github.javaparser.ast.body.VariableDeclarator::getNameAsString)
                        .forEach(candidates::add);
            }
            if (candidates.isEmpty()) return true;

            Set<String> initializedInLifecycle = new java.util.HashSet<>();
            targetType.getConstructors().forEach(constructor ->
                    collectAssignedFields(constructor, candidates, initializedInLifecycle));
            targetType.getMethods().stream()
                    .filter(this::isLifecycleMethod)
                    .forEach(method -> collectAssignedFields(method, candidates, initializedInLifecycle));
            targetType.getMembers().stream()
                    .filter(member -> member instanceof com.github.javaparser.ast.body.InitializerDeclaration)
                    .forEach(member -> collectAssignedFields(member, candidates, initializedInLifecycle));

            for (String fieldName : candidates) {
                if (!referencesField(testMethod, fieldName)) continue;
                if (initializedInLifecycle.contains(fieldName)) continue;
                var firstUse = firstFieldUse(testMethod, fieldName);
                var firstAssignment = firstFieldAssignment(testMethod, fieldName);
                if (firstUse.isEmpty() || firstAssignment.isEmpty()
                        || !isBefore(firstAssignment.get(), firstUse.get())) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void collectAssignedFields(com.github.javaparser.ast.Node node,
            Set<String> candidates, Set<String> assigned) {
        node.findAll(com.github.javaparser.ast.expr.AssignExpr.class).forEach(assignment -> {
            if (!isUnconditionalAssignment(assignment, node)) return;
            String name = assignedFieldName(assignment.getTarget());
            if (name == null || !candidates.contains(name)
                    || assignment.getOperator() != com.github.javaparser.ast.expr.AssignExpr.Operator.ASSIGN) {
                return;
            }
            if (assignment.getTarget().isNameExpr()) {
                var owner = assignment.findAncestor(com.github.javaparser.ast.body.MethodDeclaration.class).orElse(null);
                if (owner != null && isShadowedAt(
                        assignment.getTarget().asNameExpr(), owner, name)) {
                    return;
                }
                var constructor = assignment.findAncestor(
                        com.github.javaparser.ast.body.ConstructorDeclaration.class).orElse(null);
                var initializer = assignment.findAncestor(
                        com.github.javaparser.ast.body.InitializerDeclaration.class).orElse(null);
                if (constructor != null || initializer != null) {
                    return;
                }
            }
            if (assignment.getValue().isNullLiteralExpr()) return;
            assigned.add(name);
        });
    }

    private boolean isLifecycleMethod(com.github.javaparser.ast.body.MethodDeclaration method) {
        return method.getAnnotations().stream()
                .map(annotation -> annotation.getNameAsString())
                .map(name -> name.substring(name.lastIndexOf('.') + 1))
                .anyMatch(name -> switch (name) {
                    case "BeforeEach", "BeforeAll", "Before", "BeforeClass" -> true;
                    default -> false;
                });
    }

    private boolean referencesField(
            com.github.javaparser.ast.body.MethodDeclaration method, String fieldName) {
        return firstFieldUse(method, fieldName).isPresent();
    }

    private java.util.Optional<com.github.javaparser.ast.Node> firstFieldUse(
            com.github.javaparser.ast.body.MethodDeclaration method, String fieldName) {
        List<com.github.javaparser.ast.Node> uses = new ArrayList<>();
        method.findAll(com.github.javaparser.ast.expr.NameExpr.class).stream()
                .filter(name -> fieldName.equals(name.getNameAsString()))
                .filter(name -> !isShadowedAt(name, method, fieldName))
                .filter(name -> !isWriteOnlyAssignmentTarget(name))
                .forEach(uses::add);
        method.findAll(com.github.javaparser.ast.expr.FieldAccessExpr.class).stream()
                .filter(field -> field.getScope().isThisExpr())
                .filter(field -> fieldName.equals(field.getNameAsString()))
                .filter(field -> !isWriteOnlyAssignmentTarget(field))
                .forEach(uses::add);
        return uses.stream().min(this::comparePosition);
    }

    private java.util.Optional<com.github.javaparser.ast.Node> firstFieldAssignment(
            com.github.javaparser.ast.body.MethodDeclaration method, String fieldName) {
        return method.findAll(com.github.javaparser.ast.expr.AssignExpr.class).stream()
                .filter(assignment -> isFieldAssignment(assignment, method, fieldName))
                .filter(assignment -> assignment.getOperator() == com.github.javaparser.ast.expr.AssignExpr.Operator.ASSIGN)
                .map(assignment -> (com.github.javaparser.ast.Node) assignment)
                .min(this::comparePosition);
    }

    private boolean isFieldAssignment(
            com.github.javaparser.ast.expr.AssignExpr assignment,
            com.github.javaparser.ast.body.MethodDeclaration method,
            String fieldName) {
        var target = assignment.getTarget();
        if (target.isFieldAccessExpr() && target.asFieldAccessExpr().getScope().isThisExpr()) {
            return fieldName.equals(target.asFieldAccessExpr().getNameAsString())
                    && isUnconditionalAssignment(assignment, method)
                    && !assignment.getValue().isNullLiteralExpr();
        }
        return target.isNameExpr()
                && fieldName.equals(target.asNameExpr().getNameAsString())
                && isUnconditionalAssignment(assignment, method)
                && !isShadowedAt(target.asNameExpr(), method, fieldName)
                && !assignment.getValue().isNullLiteralExpr();
    }

    private boolean isUnconditionalAssignment(
            com.github.javaparser.ast.expr.AssignExpr assignment,
            com.github.javaparser.ast.Node owner) {
        if (assignment.getParentNode()
                .filter(com.github.javaparser.ast.stmt.ExpressionStmt.class::isInstance)
                .isEmpty()) {
            return false;
        }
        var current = assignment.getParentNode().orElse(null);
        while (current != null && current != owner) {
            if (current instanceof com.github.javaparser.ast.expr.LambdaExpr
                    || current instanceof com.github.javaparser.ast.stmt.IfStmt
                    || current instanceof com.github.javaparser.ast.stmt.SwitchEntry
                    || current instanceof com.github.javaparser.ast.stmt.ForStmt
                    || current instanceof com.github.javaparser.ast.stmt.ForEachStmt
                    || current instanceof com.github.javaparser.ast.stmt.WhileStmt
                    || current instanceof com.github.javaparser.ast.stmt.DoStmt
                    || current instanceof com.github.javaparser.ast.stmt.TryStmt
                    || current instanceof com.github.javaparser.ast.stmt.CatchClause
                    || current instanceof com.github.javaparser.ast.expr.ConditionalExpr
                    || current instanceof com.github.javaparser.ast.body.MethodDeclaration
                    || current instanceof com.github.javaparser.ast.body.ConstructorDeclaration
                    || current instanceof com.github.javaparser.ast.body.InitializerDeclaration) {
                return false;
            }
            current = current.getParentNode().orElse(null);
        }
        return current == owner;
    }
    private boolean isShadowedAt(
            com.github.javaparser.ast.expr.NameExpr reference,
            com.github.javaparser.ast.body.MethodDeclaration method,
            String fieldName) {
        if (method.getParameters().stream()
                .anyMatch(parameter -> fieldName.equals(parameter.getNameAsString()))) {
            return true;
        }
        for (com.github.javaparser.ast.body.VariableDeclarator variable :
                method.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)) {
            if (!fieldName.equals(variable.getNameAsString())
                    || !isBefore(variable, reference)) continue;
            com.github.javaparser.ast.Node declarationScope = variableScope(variable, method);
            com.github.javaparser.ast.Node referenceScope = reference.findAncestor(com.github.javaparser.ast.stmt.BlockStmt.class).map(block -> (com.github.javaparser.ast.Node) block).orElse(method);
            if (isAncestorOrSame(declarationScope, referenceScope)) return true;
        }
        var current = reference.getParentNode().orElse(null);
        while (current != null && current != method) {
            if (current instanceof com.github.javaparser.ast.expr.LambdaExpr lambda
                    && lambda.getParameters().stream()
                            .anyMatch(parameter -> fieldName.equals(parameter.getNameAsString()))) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    private com.github.javaparser.ast.Node variableScope(
            com.github.javaparser.ast.body.VariableDeclarator variable,
            com.github.javaparser.ast.body.MethodDeclaration method) {
        var enhancedFor = variable.findAncestor(com.github.javaparser.ast.stmt.ForEachStmt.class).orElse(null);
        if (enhancedFor != null) return enhancedFor;
        var classicFor = variable.findAncestor(com.github.javaparser.ast.stmt.ForStmt.class).orElse(null);
        if (classicFor != null) return classicFor;
        return variable.findAncestor(com.github.javaparser.ast.stmt.BlockStmt.class)
                .map(block -> (com.github.javaparser.ast.Node) block).orElse(method);
    }

    private boolean isAncestorOrSame(
            com.github.javaparser.ast.Node ancestor, com.github.javaparser.ast.Node node) {
        var current = node;
        while (current != null) {
            if (current == ancestor) return true;
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    private String assignedFieldName(com.github.javaparser.ast.expr.Expression target) {
        if (target.isNameExpr()) return target.asNameExpr().getNameAsString();
        if (target.isFieldAccessExpr() && target.asFieldAccessExpr().getScope().isThisExpr()) {
            return target.asFieldAccessExpr().getNameAsString();
        }
        return null;
    }

    private boolean isWriteOnlyAssignmentTarget(com.github.javaparser.ast.Node node) {
        return node.getParentNode()
                .filter(com.github.javaparser.ast.expr.AssignExpr.class::isInstance)
                .map(com.github.javaparser.ast.expr.AssignExpr.class::cast)
                .filter(assignment -> assignment.getTarget().equals(node))
                .map(assignment -> assignment.getOperator()
                        == com.github.javaparser.ast.expr.AssignExpr.Operator.ASSIGN)
                .orElse(false);
    }

    private int comparePosition(com.github.javaparser.ast.Node left, com.github.javaparser.ast.Node right) {
        var leftPosition = left.getBegin().orElse(null);
        var rightPosition = right.getBegin().orElse(null);
        if (leftPosition == null || rightPosition == null) return 0;
        int line = Integer.compare(leftPosition.line, rightPosition.line);
        return line != 0 ? line : Integer.compare(leftPosition.column, rightPosition.column);
    }

    private boolean isBefore(com.github.javaparser.ast.Node left, com.github.javaparser.ast.Node right) {
        return comparePosition(left, right) < 0;
    }

    private boolean isManagedFixture(com.github.javaparser.ast.body.FieldDeclaration field) {
        return field.getAnnotations().stream()
                .map(annotation -> annotation.getNameAsString())
                .map(name -> name.substring(name.lastIndexOf('.') + 1))
                .anyMatch(name -> switch (name) {
                    case "Mock", "InjectMocks", "Spy", "Captor", "Autowired", "Inject", "Resource" -> true;
                    default -> false;
                });
    }
    private boolean containsExactlyOneRequestedTest(GeneratedUnitTestDto test){
        try{
            var result=new com.github.javaparser.JavaParser().parse(test.sourceCode());
            var unit=result.getResult().filter(ignored->result.isSuccessful()).orElse(null);
            if(unit==null)return false;
            var packageName=unit.getPackageDeclaration()
                    .map(declaration->declaration.getNameAsString()).orElse("");
            if(!test.packageName().equals(packageName))return false;
            var targetType=unit.getTypes().stream()
                    .filter(type->test.testClassName().equals(type.getNameAsString())).findFirst().orElse(null);
            if(targetType==null)return false;
            var testMethods=junitTestMethods(unit,importsJunitTest(unit));
            return testMethods.size()==1
                    &&targetType.getMethods().contains(testMethods.get(0))
                    &&test.testMethodName().equals(testMethods.get(0).getNameAsString());
        }catch(Exception ignored){
            return false;
        }
    }

    private boolean importsJunitTest(com.github.javaparser.ast.CompilationUnit unit){
        return unit.getImports().stream().anyMatch(importDeclaration->
                !importDeclaration.isStatic()&&(
                        "org.junit.Test".equals(importDeclaration.getNameAsString())
                        ||"org.junit.jupiter.api.Test".equals(importDeclaration.getNameAsString())
                        ||(importDeclaration.isAsterisk()
                                &&("org.junit".equals(importDeclaration.getNameAsString())
                                        ||"org.junit.jupiter.api".equals(importDeclaration.getNameAsString())))));
    }

    private List<com.github.javaparser.ast.body.MethodDeclaration> junitTestMethods(
            com.github.javaparser.ast.CompilationUnit unit,boolean imported){
        return unit.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).stream()
                .filter(method->method.getAnnotations().stream().anyMatch(annotation->{
                    String name=annotation.getNameAsString();
                    return "org.junit.Test".equals(name)
                            ||"org.junit.jupiter.api.Test".equals(name)
                            ||(imported&&"Test".equals(name));
                })).toList();
    }
    private boolean validIdentifier(String value){return value!=null&&javax.lang.model.SourceVersion.isIdentifier(value)&&!javax.lang.model.SourceVersion.isKeyword(value);}
    private boolean validPackage(String value){return value!=null&&!value.isBlank()&&java.util.Arrays.stream(value.split("\\.",-1)).allMatch(this::validIdentifier);}
    private boolean approvedCase(TestCase c,Long projectId){TestPlan p=plans.findById(c.getTestPlanId()).orElse(null); return p!=null&&projectId.equals(p.getProjectId())&&c.getStatus()==ReviewStatus.APPROVED;}
    private UnitTest from(GeneratedUnitTestDto x){UnitTest u=new UnitTest();u.setTestCaseId(x.caseId());u.setTestClassName(x.testClassName());u.setTestMethodName(x.testMethodName());u.setPackageName(x.packageName());u.setGenerationType(x.generationType());u.setSourceCode(withTraceComment(x));u.setFilePath("src/test/java/"+x.packageName().replace('.','/')+"/"+x.testClassName()+".java"); return u;}
    private String withTraceComment(GeneratedUnitTestDto x){
        if(x.sourceCode().contains("// GreyTest trace:")) return x.sourceCode();
        TestCase testCase=cases.findById(x.caseId()).orElse(null);
        if(testCase==null) return x.sourceCode();
        String trace="// GreyTest trace: "+testCase.getCaseCode()+" | "+testCase.getTraceSource();
        return trace+"\n"+x.sourceCode();
    }
    private UnitTest from(GeneratedUnitTestDto x,String generationType){UnitTest u=from(x);u.setGenerationType(generationType);return u;}
    /** Gộp unit test theo test class thành file hoàn chỉnh như trong project thật. */
    @Transactional(readOnly=true) public List<com.greytest.dto.UnitTestFileDto> listFiles(Long projectId){
        ensure(projectId);
        var approved=cases.findAll().stream().filter(c->approvedCase(c,projectId)).toList();
        var codeByCaseId=approved.stream().collect(java.util.stream.Collectors.toMap(TestCase::getId,TestCase::getCaseCode));
        var tests=approved.stream().map(c->units.findByTestCaseId(c.getId())).filter(java.util.Objects::nonNull).toList();
        return files.mergeByClass(tests).stream()
                .map(f->new com.greytest.dto.UnitTestFileDto(f.filePath(),f.testClassName(),f.packageName(),f.caseIds().size(),
                        f.caseIds().stream().map(codeByCaseId::get).filter(java.util.Objects::nonNull).toList(),f.sourceCode()))
                .toList();
    }
    @Transactional(readOnly=true) public List<com.greytest.dto.UnitTestFileDto> listFiles(Long projectId,String servicePath){
        ensure(projectId);
        var approved=approvedCases(projectId,scopeResolver.resolve(projectId,servicePath));
        var codeByCaseId=approved.stream().collect(java.util.stream.Collectors.toMap(TestCase::getId,TestCase::getCaseCode));
        var tests=approved.stream().map(c->units.findByTestCaseId(c.getId())).filter(java.util.Objects::nonNull).toList();
        return files.mergeByClass(tests).stream()
                .map(f->new com.greytest.dto.UnitTestFileDto(f.filePath(),f.testClassName(),f.packageName(),f.caseIds().size(),
                        f.caseIds().stream().map(codeByCaseId::get).filter(java.util.Objects::nonNull).toList(),f.sourceCode()))
                .toList();
    }

    /** Đóng gói toàn bộ file test đã gộp thành ZIP để user tải về chạy JaCoCo local. */
    @Transactional(readOnly=true) public byte[] zipFiles(Long projectId){
        var mergedFiles=listFiles(projectId);
        var bos=new java.io.ByteArrayOutputStream();
        try(var zos=new java.util.zip.ZipOutputStream(bos)){
            for(var f:mergedFiles){
                zos.putNextEntry(new java.util.zip.ZipEntry(safeEntryName(f.filePath())));
                zos.write(f.sourceCode().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }catch(java.io.IOException e){throw new com.greytest.exception.StorageException("Khong tao duoc ZIP unit test",e);}
        return bos.toByteArray();
    }

    @Transactional(readOnly=true) public byte[] zipFiles(Long projectId,String servicePath){
        var mergedFiles=listFiles(projectId,servicePath);
        var bos=new java.io.ByteArrayOutputStream();
        try(var zos=new java.util.zip.ZipOutputStream(bos)){
            for(var f:mergedFiles){
                zos.putNextEntry(new java.util.zip.ZipEntry(safeEntryName(f.filePath())));
                zos.write(f.sourceCode().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }catch(java.io.IOException e){throw new com.greytest.exception.StorageException("Khong tao duoc ZIP unit test",e);}
        return bos.toByteArray();
    }

    private String safeEntryName(String filePath){
        if(filePath==null||filePath.isBlank()) throw new com.greytest.exception.StorageException("Duong dan Unit Test khong hop le",null);
        var root=java.nio.file.Path.of("src","test","java");
        var path=java.nio.file.Path.of(filePath).normalize();
        if(path.isAbsolute()||!path.startsWith(root)) throw new com.greytest.exception.StorageException("Duong dan Unit Test khong hop le",null);
        return path.toString().replace('\\','/');
    }

    private Project ensure(Long id){return projects.findById(id).orElseThrow(()->new ProjectNotFoundException(id));}
    private UnitTestDto dto(UnitTest u){return new UnitTestDto(u.getId(),u.getTestCaseId(),u.getTestClassName(),u.getTestMethodName(),u.getPackageName(),u.getGenerationType(),u.getExistingTestFilePath(),u.getSourceCode(),u.getFilePath(),u.getCreatedAt());}
}
