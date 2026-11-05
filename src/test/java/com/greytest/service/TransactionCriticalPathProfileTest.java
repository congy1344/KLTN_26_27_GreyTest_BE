package com.greytest.service;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Điều tra transaction test-only: cùng source/fixture và provider 0 ms, không đổi production runtime. */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="transaction.profile",matches="true")
class TransactionCriticalPathProfileTest extends OfflineSourceUpdateBenchmarkTest {
    @Override @Test void profileCriticalPath() throws Throwable {
        assertThat(System.getenv("LLM_PROVIDER")).isEqualTo("mock");
        assertThat(System.getenv("LLM_API_KEY")).isNullOrEmpty();
        assertThat(org.mockito.Mockito.mockingDetails(provider).isMock()).isTrue();
        assertThat(application.getBeansOfType(com.greytest.service.agent.OpenAiLlmClient.class)).isEmpty();
        assertThat(application.getBeansOfType(com.greytest.service.agent.GoogleLlmClient.class)).isEmpty();
        assertThat(System.getProperty("greytest.source-update.single-statement-lease-check-enabled")).isEqualTo("false");
        assertThat(Long.getLong("offline.delay",0)).isZero();
        boolean instrumented=Boolean.parseBoolean(System.getProperty("benchmark.instrumentation","true"));
        assertThat(TailLatencyObserver.enabled()).isEqualTo(instrumented);
        assertThat(Boolean.getBoolean("benchmark.transaction-detail")).isEqualTo(instrumented);
        int count=Integer.getInteger("transaction.samples",20);assertThat(count).isGreaterThanOrEqualTo(20);
        int before=jdbc.queryForObject("select count(*) from project",Integer.class);
        Path output=Path.of(System.getProperty("e2e.outputDirectory","target/phase3-7/profile"));Files.createDirectories(output);
        List<Map<String,Object>> samples=new ArrayList<>(),quality=new ArrayList<>();
        String sourceProof=null;com.fasterxml.jackson.databind.JsonNode semanticProof=null;
        var evidence=new LinkedHashMap<String,Object>();
        try(var observer=TailLatencyObserver.startIfEnabled()) {
            if(observer!=null) evidence.put("databaseBeforeCohort",json.readTree(observer.statistics()));
            for(int sample=-1;sample<count;sample++) {
                Fixture fixture=createFixture();
                try {
                    String digest=fixtureSourceDigest(fixture);
                    if(sourceProof==null) sourceProof=digest;else assertThat(digest).isEqualTo(sourceProof);
                    var beforeStats=observer==null?null:json.readTree(observer.statistics());
                    var cold=measureProfile(fixture,"SIX_INDEPENDENT",true,false);
                    var afterStats=observer==null?null:json.readTree(observer.statistics());
                    var warm=measureProfile(fixture,"SIX_INDEPENDENT_WARM",false,false);
                    assertThat(cold.get("changedMethods")).isEqualTo(6);assertThat(cold.get("llmCalls")).isEqualTo(7);
                    assertThat(warm.get("llmCalls")).isEqualTo(0);
                    assertThat(warm.get("outputDigest")).isEqualTo(cold.get("outputDigest"));
                    assertThat(warm.get("provenanceDigest")).isEqualTo(cold.get("provenanceDigest"));
                    assertThat(fixtureSourceDigest(fixture)).isEqualTo(digest);
                    var semantic=semanticEvidence(fixture);
                    if(semanticProof==null) semanticProof=semantic;else assertThat(semantic).isEqualTo(semanticProof);
                    if(instrumented) {
                        @SuppressWarnings("unchecked") var spans=(List<CriticalPathProfiler.Span>)cold.get("spans");
                        assertThat(spans).anyMatch(s->s.name().equals("hibernate.flush"));
                        assertThat((List<?>)cold.get("transactionEvents")).isNotEmpty();
                        assertThat((List<?>)cold.get("observerErrors")).isEmpty();
                        assertThat((List<?>)warm.get("observerErrors")).isEmpty();
                        cold.put("databaseBeforeRoot",beforeStats);cold.put("databaseAfterRoot",afterStats);
                    }
                    if(sample==-1) {
                        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("original-warmup-proposals.json").toFile(),
                            items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(fixture.updateId()).stream()
                                .map(i->Map.of("type",i.getTargetType().name(),"after",Objects.toString(i.getAfterData(),""))).toList());
                        long started=System.nanoTime();int executed=executeGeneratedTests(fixture);
                        quality.add(Map.of("generatedUnitTestsExecuted",executed,"compileAndJunitMillis",(System.nanoTime()-started)/1e6));
                    } else for(var window:List.of(cold,warm)) {
                        window.put("sample",sample);window.put("sourceFixtureDigest",digest);window.put("semanticEvidence",semantic);samples.add(window);
                    }
                } finally {cleanupFixture(fixture);}
            }
            if(observer!=null) evidence.put("databaseAfterCohort",json.readTree(observer.statistics()));
        }
        assertThat(jdbc.queryForObject("select count(*) from project",Integer.class)).isEqualTo(before);
        evidence.put("mode","OFFLINE_TRANSACTION_INVESTIGATION");evidence.put("instrumented",instrumented);
        evidence.put("samplesPerWorkload",count);evidence.put("workerConcurrency",2);evidence.put("mockDelayMillis",0);
        evidence.put("assertLeaseOptimization",false);evidence.put("realProviderRequests",0);evidence.put("qualityRuns",quality);evidence.put("samples",samples);
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("samples.json").toFile(),evidence);
    }
}
