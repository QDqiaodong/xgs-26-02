package com.example.coursematch.controller;

import com.example.coursematch.entity.OperationLog;
import com.example.coursematch.entity.Role;
import com.example.coursematch.entity.User;
import com.example.coursematch.repository.OperationLogRepository;
import com.example.coursematch.repository.RoleRepository;
import com.example.coursematch.repository.UserRepository;
import com.example.coursematch.security.AuthUser;
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
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 操作日志组合检索与查询视图稳定性集成测试（H2 + MySQL 兼容模式）。
 * 覆盖：多条件交集、关键词三类目标、单边日期范围、日期反转提示、
 * 持续写入下的稳定分页与总数、同刻日志确定性顺序、用户视图隔离、详情按 ID 精确打开。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationLogQueryIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OperationLogRepository logRepository;
    @Autowired private TokenStore tokenStore;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setUp() {
        logRepository.deleteAll();
        aliceToken = token("alice");
        bobToken = token("bob");
    }

    private String token(String username) {
        Role role = roleRepository.findByName("ADMIN").orElseGet(() ->
                roleRepository.save(Role.builder().name("ADMIN").description("ADMIN").active(true).build()));
        User user = userRepository.findByUsername(username).orElseGet(() ->
                userRepository.save(User.builder()
                        .username(username)
                        .password(passwordEncoder.encode("x"))
                        .realName(username)
                        .roles(Set.of(role))
                        .enabled(true)
                        .build()));
        return tokenStore.create(AuthUser.builder()
                .userId(user.getId())
                .username(username)
                .realName(username)
                .roles(Set.of("ADMIN"))
                .build());
    }

    private OperationLog saveLog(String type, String targetType, Long targetId,
                                 String operator, String details, String result,
                                 LocalDateTime time) {
        return logRepository.save(OperationLog.builder()
                .operationType(type)
                .targetType(targetType)
                .targetId(targetId)
                .operator(operator)
                .details(details)
                .result(result)
                .operationTime(time)
                .build());
    }

    private JsonNode query(String token, String queryString) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/logs?" + queryString)
                        .header("Authorization", token == null ? "" : token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.path("data");
    }

    private List<Long> ids(JsonNode data) {
        return java.util.stream.StreamSupport.stream(data.path("content").spliterator(), false)
                .map(n -> n.path("id").asLong())
                .toList();
    }

    @Test
    void allNonEmptyConditionsApplyAsIntersection() throws Exception {
        saveLog("MATCH", "COURSE", 1L, "alice", "执行匹配课程1", "SUCCESS",
                LocalDateTime.of(2026, 9, 1, 10, 0));
        saveLog("MATCH", "INSTRUCTOR", 2L, "alice", "执行匹配讲师", "SUCCESS",
                LocalDateTime.of(2026, 9, 1, 11, 0));
        saveLog("CREATE", "COURSE", 1L, "alice", "创建课程1", "SUCCESS",
                LocalDateTime.of(2026, 9, 1, 12, 0));
        saveLog("MATCH", "COURSE", 1L, "bob", "执行匹配课程1", "SUCCESS",
                LocalDateTime.of(2026, 9, 1, 13, 0));

        // 操作类型 + 目标类型 + 操作人 三个条件必须同时生效，而不是只取其一
        JsonNode data = query(aliceToken,
                "operationType=MATCH&targetType=COURSE&operator=alice&size=50");
        assertEquals(1, data.path("totalElements").asLong());
        assertEquals(1L, data.path("content").get(0).path("targetId").asLong());
        assertEquals("MATCH", data.path("content").get(0).path("operationType").asText());
    }

    @Test
    void keywordMatchesOperatorDetailsAndExplicitTargetId() throws Exception {
        saveLog("UPDATE", "COURSE", 77L, "alice", "把课程名称改为新课", "SUCCESS",
                LocalDateTime.of(2026, 9, 2, 10, 0));
        saveLog("DELETE", "INSTRUCTOR", 88L, "charlie", "删除讲师资料", "SUCCESS",
                LocalDateTime.of(2026, 9, 2, 11, 0));
        saveLog("MATCH", "COURSE", 99L, "bob", "执行智能匹配", "SUCCESS",
                LocalDateTime.of(2026, 9, 2, 12, 0));

        // 关键词命中操作详情
        JsonNode byDetails = query(null, "keyword=课程名称&size=50");
        assertEquals(1, byDetails.path("totalElements").asLong());

        // 关键词命中操作人（忽略大小写）
        JsonNode byOperator = query(null, "keyword=ALICE&size=50");
        assertEquals(1, byOperator.path("totalElements").asLong());

        // 纯数字关键词命中明确的目标编号
        JsonNode byTargetId = query(null, "keyword=88&size=50");
        assertEquals(1, byTargetId.path("totalElements").asLong());
        assertEquals(88L, byTargetId.path("content").get(0).path("targetId").asLong());
    }

    @Test
    void oneSidedDateRangeNarrowsResults() throws Exception {
        saveLog("CREATE", "COURSE", 1L, "alice", "9月1日", "SUCCESS",
                LocalDateTime.of(2026, 9, 1, 9, 0));
        saveLog("UPDATE", "COURSE", 2L, "alice", "9月5日", "SUCCESS",
                LocalDateTime.of(2026, 9, 5, 9, 0));
        saveLog("DELETE", "COURSE", 3L, "alice", "9月10日", "SUCCESS",
                LocalDateTime.of(2026, 9, 10, 9, 0));

        // 只有开始日期：包含开始日当天 00:00 起的全部记录
        JsonNode startOnly = query(null, "startDate=2026-09-05&size=50");
        assertEquals(2, startOnly.path("totalElements").asLong());

        // 只有结束日期：包含结束日当天 23:59:59 前的全部记录
        JsonNode endOnly = query(null, "endDate=2026-09-05&size=50");
        assertEquals(2, endOnly.path("totalElements").asLong());

        // 单边日期再叠加操作类型，仍是交集
        JsonNode combined = query(null, "startDate=2026-09-05&operationType=DELETE&size=50");
        assertEquals(1, combined.path("totalElements").asLong());
    }

    @Test
    void startAfterEndReturnsUnderstandable400InsteadOfEmptyPage() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/logs")
                        .param("startDate", "2026-09-10")
                        .param("endDate", "2026-09-01"))
                .andExpect(status().isBadRequest())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(body.path("message").asText().contains("开始日期不能晚于结束日期"),
                "日期反转应返回可理解的提示");
    }

    @Test
    void sameTimestampLogsHaveDeterministicOrder() {
        LocalDateTime same = LocalDateTime.of(2026, 9, 3, 8, 0);
        OperationLog a = saveLog("CREATE", "COURSE", 1L, "alice", "同刻A", "SUCCESS", same);
        OperationLog b = saveLog("UPDATE", "COURSE", 2L, "alice", "同刻B", "SUCCESS", same);
        OperationLog c = saveLog("DELETE", "COURSE", 3L, "alice", "同刻C", "SUCCESS", same);

        // 时间相同时按 ID 倒序，多次查询结果一致，不依赖数据库未定义顺序
        JsonNode data1 = queryUnchecked(null, "size=50");
        JsonNode data2 = queryUnchecked(null, "size=50");
        assertEquals(List.of(c.getId(), b.getId(), a.getId()), ids(data1));
        assertEquals(ids(data1), ids(data2));
    }

    @Test
    void newLogsDuringPagingDoNotShiftViewOrTotal() throws Exception {
        // 初始 15 条，每页 10 条
        for (int i = 1; i <= 15; i++) {
            saveLog("CREATE", "COURSE", (long) i, "alice", "初始日志" + i, "SUCCESS",
                    LocalDateTime.of(2026, 9, 4, 10, i));
        }

        JsonNode first = query(aliceToken, "size=10&page=0");
        String token = first.path("viewToken").asText();
        assertEquals(15, first.path("totalElements").asLong());
        assertEquals(2, first.path("totalPages").asInt());
        List<Long> firstPageIds = ids(first);
        assertEquals(10, firstPageIds.size());
        long lastOfFirstPage = firstPageIds.get(firstPageIds.size() - 1);

        // 后台异步写入 3 条新的匹配日志（含 1 条符合当前筛选的）
        saveLog("CREATE", "COURSE", 16L, "alice", "新到的匹配日志", "SUCCESS",
                LocalDateTime.of(2026, 9, 4, 11, 0));
        saveLog("UPDATE", "INSTRUCTOR", 17L, "bob", "新到的无关目标类型", "SUCCESS",
                LocalDateTime.of(2026, 9, 4, 11, 1));
        saveLog("CREATE", "COURSE", 18L, "bob", "新到的无关操作人", "SUCCESS",
                LocalDateTime.of(2026, 9, 4, 11, 2));

        // 翻到第二页：复用视图，结果集与总数都不被新日志顶动
        JsonNode second = query(aliceToken, "size=10&page=1&viewToken=" + token);
        assertEquals(token, second.path("viewToken").asText());
        assertEquals(15, second.path("totalElements").asLong());
        List<Long> secondPageIds = ids(second);
        assertEquals(5, secondPageIds.size());
        // 上一页末尾不能在第二页重复
        assertFalse(secondPageIds.contains(lastOfFirstPage));
        // 视图内两页合计恰为初始 15 条，没有漏记录
        java.util.Set<Long> union = new java.util.TreeSet<>();
        union.addAll(firstPageIds);
        union.addAll(secondPageIds);
        assertEquals(15, union.size());

        // 再查第一页，顺序与首次完全一致
        JsonNode firstAgain = query(aliceToken, "size=10&page=0&viewToken=" + token);
        assertEquals(firstPageIds, ids(firstAgain));
        assertEquals(15, firstAgain.path("totalElements").asLong());

        // 不带视图令牌（用户主动刷新/重新搜索）建立新视图，新增记录与新总数出现
        JsonNode refreshed = query(aliceToken, "size=10&page=0");
        assertNotEquals(token, refreshed.path("viewToken").asText());
        assertEquals(18, refreshed.path("totalElements").asLong());
        assertTrue(refreshed.path("newSinceView").asLong() >= 1);
    }

    @Test
    void changedFilterRejectsOldViewAndBuildsNewOne() throws Exception {
        for (int i = 1; i <= 5; i++) {
            saveLog("CREATE", "COURSE", (long) i, "alice", "课程创建", "SUCCESS",
                    LocalDateTime.of(2026, 9, 5, 10, i));
            saveLog("DELETE", "INSTRUCTOR", (long) i, "bob", "讲师删除", "SUCCESS",
                    LocalDateTime.of(2026, 9, 5, 11, i));
        }
        JsonNode courseView = query(aliceToken, "targetType=COURSE&size=50");
        String token = courseView.path("viewToken").asText();
        assertEquals(5, courseView.path("totalElements").asLong());

        // 携带旧视图令牌但改了筛选条件：必须按新条件重建视图，不能沿用旧总数
        JsonNode switched = query(aliceToken, "targetType=INSTRUCTOR&size=50&viewToken=" + token);
        assertEquals(5, switched.path("totalElements").asLong());
        assertNotEquals(token, switched.path("viewToken").asText());
        for (JsonNode row : switched.path("content")) {
            assertEquals("INSTRUCTOR", row.path("targetType").asText());
        }
    }

    @Test
    void viewsAreIsolatedBetweenUsers() throws Exception {
        saveLog("CREATE", "COURSE", 1L, "alice", "alice 的课程操作", "SUCCESS",
                LocalDateTime.of(2026, 9, 6, 10, 0));
        saveLog("CREATE", "COURSE", 2L, "bob", "bob 的课程操作", "SUCCESS",
                LocalDateTime.of(2026, 9, 6, 10, 1));

        // alice 只查自己的操作并建立视图
        JsonNode aliceResult = query(aliceToken, "operator=alice&size=50");
        String aliceView = aliceResult.path("viewToken").asText();
        assertEquals(1, aliceResult.path("totalElements").asLong());

        // bob 即使拿到 alice 的视图令牌，也不能复用（归属不同用户），按自己的条件重建
        JsonNode bobResult = query(bobToken, "operator=bob&size=50&viewToken=" + aliceView);
        assertNotEquals(aliceView, bobResult.path("viewToken").asText());
        assertEquals(1, bobResult.path("totalElements").asLong());
        assertEquals("bob", bobResult.path("content").get(0).path("operator").asText());
    }

    @Test
    void detailEndpointOpensExactlyTheClickedLog() throws Exception {
        OperationLog one = saveLog("MATCH", "COURSE", 1L, "alice", "先产生", "SUCCESS",
                LocalDateTime.of(2026, 9, 7, 10, 0));
        OperationLog two = saveLog("MATCH", "COURSE", 2L, "alice", "后产生", "SUCCESS",
                LocalDateTime.of(2026, 9, 7, 10, 1));

        // 点的是哪一行，详情接口就按行 ID 返回哪一条，与新日志是否插入无关
        MvcResult result = mockMvc.perform(get("/api/logs/" + one.getId()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertEquals(one.getId(), data.path("id").asLong());
        assertEquals("先产生", data.path("details").asText());
        assertNotEquals(two.getId(), data.path("id").asLong());

        mockMvc.perform(get("/api/logs/999999")).andExpect(status().isOk());
    }

    @Test
    void blankFilterParamsAreTreatedAsAbsent() throws Exception {
        saveLog("CREATE", "COURSE", 1L, "alice", "一条日志", "SUCCESS",
                LocalDateTime.of(2026, 9, 8, 10, 0));
        JsonNode data = query(null, "operationType=&targetType=&operator=&keyword=&size=50");
        assertEquals(1, data.path("totalElements").asLong());
    }

    @Test
    void pageSizeChangeStaysInSameViewAndRecomputesFromFirstPage() throws Exception {
        for (int i = 1; i <= 25; i++) {
            saveLog("CREATE", "COURSE", (long) i, "alice", "日志" + i, "SUCCESS",
                    LocalDateTime.of(2026, 9, 9, 10, i % 60));
        }
        // 后台可能再写新日志
        JsonNode first = query(aliceToken, "size=10&page=0");
        String token = first.path("viewToken").asText();
        saveLog("CREATE", "COURSE", 99L, "alice", "视图建立后的新日志", "SUCCESS",
                LocalDateTime.of(2026, 9, 9, 11, 0));

        // 改每页条数为 20：同一视图令牌，总数/高水位不变；请求第 0 页得到 20 条
        JsonNode resized = query(aliceToken, "size=20&page=0&viewToken=" + token);
        assertEquals(token, resized.path("viewToken").asText());
        assertEquals(25, resized.path("totalElements").asLong());
        assertEquals(2, resized.path("totalPages").asInt());
        assertEquals(20, resized.path("content").size());
        assertFalse(ids(resized).contains(99L));

        // 第二页仍是该视图剩余的 5 条
        JsonNode last = query(aliceToken, "size=20&page=1&viewToken=" + token);
        assertEquals(5, last.path("content").size());
    }

    private JsonNode queryUnchecked(String token, String queryString) {
        try {
            return query(token, queryString);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
