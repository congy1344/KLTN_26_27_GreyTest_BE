package com.greytest;

import org.springframework.boot.builder.SpringApplicationBuilder;

/** Chạy server fixture cho System Test/UAT rehearsal/tải web; không đóng gói vào sản phẩm. */
public final class Chapter5FixtureServer {
    private Chapter5FixtureServer() { }

    public static void main(String[] args) {
        new SpringApplicationBuilder(GreytestApplication.class,
                Chapter5PipelineIntegrationTest.ProviderConfig.class).run(
                "--spring.config.import=",
                "--server.address=127.0.0.1",
                "--server.port=18081",
                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:55439/chapter5_system",
                "--spring.datasource.username=chapter5_test",
                "--spring.datasource.password=",
                "--greytest.storage.path=../outputs/chapter-5/system-20261009/storage",
                "--greytest.auth.token-secret=chapter5-fixture-server-secret-at-least-32-bytes",
                "--llm.provider=mock",
                "--llm.usage.enabled=false");
    }
}
