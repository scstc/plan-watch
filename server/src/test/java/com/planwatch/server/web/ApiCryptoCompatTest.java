package com.planwatch.server.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 兼容模式：planwatch.crypto.required=false 时明文客户端（curl 直调/旧版 app）可用。
 * 独立测试类 —— 与强制加密模式是不同的 Spring 上下文配置。
 */
@SpringBootTest(properties = "planwatch.crypto.required=false")
@AutoConfigureMockMvc
class ApiCryptoCompatTest {

    @TempDir
    static Path tempDir;

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("planwatch.data-dir", () -> tempDir.toString());
    }

    @Test
    void plaintextRequestsStillWorkWhenNotRequired() throws Exception {
        String body = mockMvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("accounts");
    }
}
