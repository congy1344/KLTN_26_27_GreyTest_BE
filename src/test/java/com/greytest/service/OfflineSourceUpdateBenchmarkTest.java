package com.greytest.service;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** Benchmark offline opt-in; dùng pipeline/fixture thật và mock xác định đã có. */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="offline.profile",matches="true")
class OfflineSourceUpdateBenchmarkTest extends LlmCriticalPathProfileTest {
    @Autowired ApplicationContext application;

    static <T> T delayed(long millis,CriticalPathProfiler.Call<T> response) throws Throwable {
        if(!List.of(0L,500L,2000L,5000L).contains(millis)) throw new IllegalArgumentException("Unsupported mock delay");
        long started=System.nanoTime();
        try { if(millis>0) Thread.sleep(millis); }
        catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw interrupted;}
        finally {GenerationJobContext.recordDuration("provider.simulatedWait",System.nanoTime()-started);}
        return response.get();
    }

    @Override String respond(String prompt) {
        long delay=Long.getLong("offline.delay",0);
        return CriticalPathProfiler.unchecked("provider.simulated",()->delayed(delay,()->super.respond(prompt)));
    }

    @Override @Test void profileCriticalPath() throws Throwable {
        assertThat(System.getenv("LLM_PROVIDER")).isEqualTo("mock");
        assertThat(System.getenv("LLM_API_KEY")).isNullOrEmpty();
        assertThat(org.mockito.Mockito.mockingDetails(provider).isMock()).isTrue();
        assertThat(application.getBeansOfType(com.greytest.service.agent.OpenAiLlmClient.class)).isEmpty();
        assertThat(application.getBeansOfType(com.greytest.service.agent.GoogleLlmClient.class)).isEmpty();
        String leaseFlag="greytest.source-update.single-statement-lease-check-enabled";
        assertThat(System.getProperty(leaseFlag)).isEqualTo("false");
        int count=Integer.getInteger("offline.samples",10);
        assertThat(count).isPositive();
        long delay=Long.getLong("offline.delay",0);
        assertThat(delay).isIn(0L,500L,2000L,5000L);
        int before=jdbc.queryForObject("select count(*) from project",Integer.class);
        Path output=Path.of(System.getProperty("e2e.outputDirectory","target/phase3-6-offline/"+delay));
        Files.createDirectories(output);
        List<Map<String,Object>> samples=new ArrayList<>(),quality=new ArrayList<>();
        for(String workload:List.of("ONE_ADDED","ONE_MODIFIED","SIX_INDEPENDENT","RESUME")) {
            for(int sample=-1;sample<count;sample++) {
                Fixture fixture=createFixture();
                try {
                    if(workload.equals("ONE_MODIFIED")) {
                        writeSource(fixture.root().resolve("candidate"),41);
                        Path file=fixture.root().resolve("candidate/src/main/java/benchmark/BenchmarkService.java");
                        Files.writeString(file,Files.readString(file).replace("public int value0() { return 41; }","public int value0() { return 42; }"));
                    } else prepare(fixture,workload);
                    String digest=fixtureSourceDigest(fixture);
                    if(workload.equals("RESUME")) failNextUnitTest.set(true);
                    var cold=measureProfile(fixture,workload,true,workload.equals("RESUME"));
                    var repeat=measureProfile(fixture,workload.equals("RESUME")?"RESUME_RETRY":workload+"_WARM",false,false);
                    var windows=new ArrayList<Map<String,Object>>(List.of(cold,repeat));
                    var settled=repeat;
                    if(workload.equals("RESUME")) {
                        settled=measureProfile(fixture,"RESUME_WARM",false,false);windows.add(settled);
                    }
                    assertThat(settled.get("llmCalls")).isEqualTo(0);
                    assertThat(settled.get("outputDigest")).isEqualTo((workload.equals("RESUME")?repeat:cold).get("outputDigest"));
                    assertThat(settled.get("provenanceDigest")).isEqualTo((workload.equals("RESUME")?repeat:cold).get("provenanceDigest"));
                    assertThat(fixtureSourceDigest(fixture)).isEqualTo(digest);
                    var semantic=semanticEvidence(fixture);
                    if(sample==-1) {
                        Path artifacts=output.resolve("quality/"+workload);Files.createDirectories(artifacts);
                        json.writerWithDefaultPrettyPrinter().writeValue(artifacts.resolve("original-proposals.json").toFile(),
                                items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(fixture.updateId()).stream().map(i->Map.of("type",i.getTargetType().name(),"after",Objects.toString(i.getAfterData(),""))).toList());
                        long started=System.nanoTime();int executed=executeGeneratedTests(fixture);
                        quality.add(Map.of("workload",workload,"generatedUnitTestsExecuted",executed,"compileAndJunitWallMillis",(System.nanoTime()-started)/1e6));
                    } else for(var window:windows) {
                        window.put("sample",sample);window.put("simulatedProviderMillis",delay);
                        window.put("sourceFixtureDigest",digest);window.put("semanticEvidence",semantic);
                        samples.add(window);
                    }
                } finally {failNextUnitTest.set(false);cleanupFixture(fixture);}
            }
        }
        assertThat(jdbc.queryForObject("select count(*) from project",Integer.class)).isEqualTo(before);
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("samples.json").toFile(),Map.of(
                "mode","OFFLINE","simulatedProviderMillis",delay,"samplesPerWorkload",count,
                "realProviderRequests",0,"assertLeaseOptimization",false,"batchConcurrency",2,"qualityRuns",quality,"samples",samples));
    }
}
