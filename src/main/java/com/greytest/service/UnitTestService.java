package com.greytest.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.greytest.dto.UnitTestDto;
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
    private final UnitTestRepository units; private final TestCaseRepository cases; private final TestPlanRepository plans; private final ProjectRepository projects; private final AIAgentService ai; private final UnitTestFileService files; private final TransactionTemplate transactions;
    public UnitTestService(UnitTestRepository units, TestCaseRepository cases, TestPlanRepository plans, ProjectRepository projects, AIAgentService ai, UnitTestFileService files, PlatformTransactionManager transactionManager){this.units=units;this.cases=cases;this.plans=plans;this.projects=projects;this.ai=ai;this.files=files;this.transactions=new TransactionTemplate(transactionManager);}
    @Transactional(readOnly=true) public List<UnitTestDto> list(Long projectId){ensure(projectId); return cases.findAll().stream().filter(c->approvedCase(c,projectId)).map(c->units.findByTestCaseId(c.getId())).filter(java.util.Objects::nonNull).map(this::dto).toList();}
    public List<UnitTestDto> generate(Long projectId){
        Project project=ensure(projectId);
        ensureCanGenerate(project);
        var approved=approvedCases(projectId);
        if(approved.isEmpty()) throw new LlmResponseException("Khong co Test Case da approve de sinh Unit Test.");
        var generated=generateBatches(projectId,approved);
        var expectedIds=approved.stream().map(TestCase::getId).collect(java.util.stream.Collectors.toSet());
        return transactions.execute(status->persistGenerated(projectId,expectedIds,generated));
    }
    /** Sinh Unit Test chỉ cho case vòng mới, giữ nguyên toàn bộ output các vòng trước. */
    public List<UnitTestDto> generateSupplemental(Long projectId,List<Long> caseIds){
        Project p=ensure(projectId);
        ensureCanGenerate(p);
        var targetIds=new java.util.HashSet<>(caseIds);
        var target=caseIds.stream().map(id->cases.findById(id).orElse(null)).filter(java.util.Objects::nonNull).filter(c->approvedCase(c,projectId)).toList();
        if(target.size()!=targetIds.size()) throw new InvalidProjectStatusException("Test Case bổ sung không hợp lệ.");
        var generated=generateBatches(projectId,target);
        return transactions.execute(status->persistSupplemental(projectId,targetIds,generated));
    }
    private List<GeneratedUnitTestDto> generateBatches(Long projectId,List<TestCase> target){
        List<GeneratedUnitTestDto> generated=new ArrayList<>();
        for(int start=0;start<target.size();start+=GenerationContextBuilder.MAX_UNIT_TEST_CASES){
            var batch=target.subList(start,Math.min(start+GenerationContextBuilder.MAX_UNIT_TEST_CASES,target.size()));
            Set<Long> ids=batch.stream().map(TestCase::getId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            var valid=ai.generateUnitTests(projectId,ids).unitTests().stream()
                    .filter(x->ids.contains(x.caseId())&&valid(x,projectId)).toList();
            var generatedIds=valid.stream().map(GeneratedUnitTestDto::caseId).collect(java.util.stream.Collectors.toSet());
            if(valid.isEmpty()||generatedIds.size()!=valid.size()||!generatedIds.equals(ids))
                throw new LlmResponseException("AI chua sinh du Unit Test cho moi Test Case da approve.");
            generated.addAll(valid);
        }
        return uniqueMethodNames(generated,Set.of());
    }
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
            unique.add(new GeneratedUnitTestDto(test.caseId(),test.testClassName(),name,test.packageName(),test.generationType(),renameMethod(test.sourceCode(),test.testMethodName(),name)));
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
    private String renameMethod(String source,String oldName,String newName){
        if(oldName.equals(newName)) return source;
        try{
            var result=new com.github.javaparser.JavaParser().parse(source);
            var unit=result.getResult().filter(ignored->result.isSuccessful()).orElse(null);
            if(unit!=null){
                for(var method:unit.findAll(com.github.javaparser.ast.body.MethodDeclaration.class)){
                    if(oldName.equals(method.getNameAsString())){
                        method.setName(newName);
                        return unit.toString();
                    }
                }
            }
        }catch(Exception ignored){
            // Nếu source AI chưa parse được, vẫn đổi tên bằng regex tối thiểu để user có file sửa tiếp.
        }
        return source.replaceFirst("(?<![A-Za-z0-9_$])"+java.util.regex.Pattern.quote(oldName)+"\\s*\\(",java.util.regex.Matcher.quoteReplacement(newName+"("));
    }
    private String methodKey(GeneratedUnitTestDto test){return test.packageName()+"\n"+test.testClassName()+"\n"+test.testMethodName();}
    private String methodKey(UnitTest test){return test.getPackageName()+"\n"+test.getTestClassName()+"\n"+test.getTestMethodName();}
    private List<UnitTestDto> persistGenerated(Long projectId,Set<Long> expectedIds,List<GeneratedUnitTestDto> generated){
        Project p=lockedProject(projectId);
        ensureCanGenerate(p);
        var approved=approvedCases(projectId);
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
    /** Xóa unit test của vòng trước để sinh lại không bị nhân đôi record. */
    private void deleteOldUnitTests(List<TestCase> approved){ approved.stream().map(c->units.findByTestCaseId(c.getId())).filter(java.util.Objects::nonNull).forEach(units::delete); units.flush(); }
    private boolean valid(GeneratedUnitTestDto x,Long projectId){if(x==null)return false; TestCase c=cases.findById(x.caseId()).orElse(null); return c!=null&&approvedCase(c,projectId)&&x.sourceCode()!=null&&!x.sourceCode().isBlank()&&validIdentifier(x.testClassName())&&validIdentifier(x.testMethodName())&&validPackage(x.packageName());}
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
