package com.example.coursematch.controller;

import com.example.coursematch.entity.OperationLog;
import com.example.coursematch.entity.Role;
import com.example.coursematch.entity.User;
import com.example.coursematch.repository.OperationLogRepository;
import com.example.coursematch.repository.OperationLogViewItemRepository;
import com.example.coursematch.repository.OperationLogViewRepository;
import com.example.coursematch.repository.RoleRepository;
import com.example.coursematch.repository.UserRepository;
import com.example.coursematch.security.TokenStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 操作日志接口的 HTTP 层测试：未登录拒绝、组合条件交集、非法日期可理解提示、
 * 翻页复用 viewId 时结果稳定。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationLogControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TokenStore tokenStore;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OperationLogRepository logRepository;
    @Autowired private OperationLogViewRepository viewRepository;
    @Autowired private OperationLogViewItemRepository viewItemRepository;

    private String token;

    @BeforeEach
    void setUp() {
        viewItemRepository.deleteAll();
        viewRepository.deleteAll();
        logRepository.deleteAll();
        Role role = roleRepository.findByName("ADMIN").orElseGet(() ->
                roleRepository.save(Role.builder().name("ADMIN").description("admin").active(true).build()));
        User user = userRepository.findByUsername("log_tester").orElseGet(() ->
                userRepository.save(User.builder()
                        .username("log_tester").password(passwordEncoder.encode("x"))
                        .realName("log_tester").roles(Set.of(role)).enabled(true).build()));
        token = tokenStore.create(com.example.coursematch.security.AuthUser.builder()
                .userId(user.getId()).username("log_tester").realName("log_tester")
                .roles(Set.of("ADMIN")).build());
    }

    private JsonNode json(String response) throws Exception {
        return objectMapper.readTree(response);
    }

    @Test
    void rejectsAnonymousAccess() throws Exception {
        mockMvc.perform(get("/api/logs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void combinedFiltersKeywordAndStablePagination() throws Exception {
        LocalDateTime base = LocalDateTime.of(2026, 9, 2, 10, 0);
        logRepository.save(OperationLog.builder()
                .operationType("MATCH").targetType("COURSE").targetId(42L)
                .operator("alice").details("对课程 #42 执行匹配").result("SUCCESS")
                .operationTime(base).build());
        logRepository.save(OperationLog.builder()
                .operationType("MATCH").targetType("INSTRUCTOR").targetId(7L)
                .operator("bob").details("无关记录").result("FAIL")
                .operationTime(base.plusHours(1)).build());

        // 组合条件 + 关键词命中目标编号
        String body = mockMvc.perform(get("/api/logs")
                        .header("Authorization", token)
                        .param("page", "0").param("size", "10")
                        .param("operationType", "MATCH")
                        .param("targetType", "COURSE")
                        .param("operator", "ali")
                        .param("keyword", "42")
                        .param("startDate", "2026-09-01")
                        .param("endDate", "2026-09-30"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        JsonNode data = json(body).path("data");
        org.junit.jupiter.api.Assertions.assertEquals(1, data.path("totalElements").asLong());
        long viewId = data.path("viewId").asLong();
        org.junit.jupiter.api.Assertions.assertTrue(data.path("viewCreated").asBoolean());
        JsonNode row = data.path("content").get(0);
        org.junit.jupiter.api.Assertions.assertEquals("对课程 #42 执行匹配", row.path("details").asText());
        org.junit.jupiter.api.Assertions.assertEquals("SUCCESS", row.path("result").asText());
        org.junit.jupiter.api.Assertions.assertFalse(row.path("operationTime").asText().isBlank());

        // 新日志持续写入，翻页仍使用同一视图，总数不变
        for (long i = 100; i < 130; i++) {
            logRepository.save(OperationLog.builder()
                    .operationType("MATCH").targetType("COURSE").targetId(i)
                    .operator("alice").details("新匹配 " + i).result("SUCCESS")
                    .operationTime(LocalDateTime.now()).build());
        }

        String pageAgain = mockMvc.perform(get("/api/logs")
                        .header("Authorization", token)
                        .param("page", "0").param("size", "10")
                        .param("operationType", "MATCH")
                        .param("targetType", "COURSE")
                        .param("operator", "ali")
                        .param("keyword", "42")
                        .param("startDate", "2026-09-01")
                        .param("endDate", "2026-09-30")
                        .param("viewId", String.valueOf(viewId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode stable = json(pageAgain).path("data");
        org.junit.jupiter.api.Assertions.assertEquals(1, stable.path("totalElements").asLong());
        org.junit.jupiter.api.Assertions.assertFalse(stable.path("viewCreated").asBoolean());
        org.junit.jupiter.api.Assertions.assertEquals(viewId, stable.path("viewId").asLong());
    }

    @Test
    void reversedDateRangeReturnsUnderstandable400() throws Exception {
        String body = mockMvc.perform(get("/api/logs")
                        .header("Authorization", token)
                        .param("startDate", "2026-09-30")
                        .param("endDate", "2026-09-01"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(
                json(body).path("message").asText().contains("开始日期不能晚于结束日期"));
    }

    @Test
    void detailOpensTheExactRowById() throws Exception {
        OperationLog log = logRepository.save(OperationLog.builder()
                .operationType("CREATE").targetType("COURSE").targetId(5L)
                .operator("alice").details("精确行详情").result("SUCCESS")
                .operationTime(LocalDateTime.of(2026, 9, 2, 10, 0)).build());

        // 之后有更新的日志插入，按 ID 取详情仍是被点击的那一行
        logRepository.save(OperationLog.builder()
                .operationType("UPDATE").targetType("COURSE").targetId(6L)
                .operator("bob").details("后插入的日志").result("SUCCESS")
                .operationTime(LocalDateTime.now()).build());

        String body = mockMvc.perform(get("/api/logs/" + log.getId())
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode data = json(body).path("data");
        org.junit.jupiter.api.Assertions.assertEquals(log.getId(), data.path("id").asLong());
        org.junit.jupiter.api.Assertions.assertEquals("精确行详情", data.path("details").asText());
    }
}
