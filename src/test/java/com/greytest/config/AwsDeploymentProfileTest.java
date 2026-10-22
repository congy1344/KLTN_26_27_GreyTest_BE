package com.greytest.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AwsDeploymentProfileTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=aws");

    @Test
    void bindsDeploymentOverridesUnderTheNamespaceUsedByServices() {
        contextRunner.withPropertyValues(
                "GREYTEST_LLM_BATCH_CONCURRENCY=1",
                "GREYTEST_LLM_CACHE_MAX_ENTRIES=80",
                "GREYTEST_AI_CONTEXT_LOG_PATH=/app/log")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var environment = context.getEnvironment();
                    assertThat(environment.getProperty("greytest.llm-batch.concurrency", Integer.class))
                            .isEqualTo(1);
                    assertThat(environment.getProperty("greytest.llm-cache.max-entries", Integer.class))
                            .isEqualTo(80);
                    assertThat(environment.getProperty("greytest.ai-context-log-path"))
                            .isEqualTo("/app/log");
                });
    }

    @Test
    void keepsPromptAndSourceLoggingDisabledForThePublicDemo() {
        contextRunner.withPropertyValues(
                "GREYTEST_AI_CONTEXT_LOG_ENABLED=true",
                "GREYTEST_AI_CONTEXT_LOG_CONSOLE_ENABLED=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var environment = context.getEnvironment();
                    assertThat(environment.getProperty("greytest.ai-context-log-enabled", Boolean.class))
                            .isFalse();
                    assertThat(environment.getProperty("greytest.ai-context-log-console-enabled", Boolean.class))
                            .isFalse();
                });
    }
}
