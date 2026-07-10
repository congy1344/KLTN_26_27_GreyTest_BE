package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.fasterxml.jackson.databind.ObjectMapper;

class LlmProviderContextTest {

    @Test
    void createsGoogleClientFromProviderProperty() {
        new ApplicationContextRunner()
                .withBean(ObjectMapper.class)
                .withUserConfiguration(GoogleLlmClient.class)
                .withPropertyValues("llm.provider=google", "llm.api-key=test")
                .run(context -> assertThat(context).hasSingleBean(LlmClient.class)
                        .getBean(LlmClient.class).isInstanceOf(GoogleLlmClient.class));
    }

    @Test
    void createsOpenAiClientFromProviderProperty() {
        new ApplicationContextRunner()
                .withBean(ObjectMapper.class)
                .withUserConfiguration(OpenAiLlmClient.class)
                .withPropertyValues("llm.provider=openai", "llm.api-key=test")
                .run(context -> assertThat(context).hasSingleBean(LlmClient.class)
                        .getBean(LlmClient.class).isInstanceOf(OpenAiLlmClient.class));
    }
}
