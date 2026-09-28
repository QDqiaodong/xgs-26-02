package com.example.coursematch;

import com.example.coursematch.entity.OperationLog;
import com.example.coursematch.entity.Role;
import com.example.coursematch.entity.User;
import com.example.coursematch.repository.OperationLogRepository;
import com.example.coursematch.repository.OperationLogViewItemRepository;
import com.example.coursematch.repository.OperationLogViewRepository;
import com.example.coursematch.repository.RoleRepository;
import com.example.coursematch.repository.UserRepository;
import com.example.coursematch.security.AuthUser;
import com.example.coursematch.security.CurrentUserProvider;
import com.example.coursematch.service.OperationLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 操作日志组合检索与稳定分页集成测试（H2 + MySQL 兼容模式）。
 *
 * 覆盖：
 * 1. 多条件按交集生效、关键词命中操作人/详情/明确目标编号；
 * 2. 单边日期收窄、开始晚于结束给出明确错误；
 * 3. 搜索会话内新日志持续写入不顶动快照（无重复、无遗漏、总数稳定）；
 * 4. 刷新建立新视图并统计新增；同一时刻多条日志顺序确定；
 * 5. 两名用户的查询视图相互隔离。
 */
@SpringBootTest
class OperationLogQueryIntegrationTest {

    @Autowired private OperationLogService logService;
    @Autowired private OperationLogRepository logRepository;
    @Autowired private OperationLogViewRepository viewRepository;
    @Autowired private OperationLogViewItemRepository viewItemRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        viewItemRepository.deleteAll();
        viewRepository.deleteAll();
        logRepository.deleteAll();
    }

    @AfterEach
    void clearContext() {
        CurrentUserProvider.clear();
    }

    private void loginAs(String username) {
        Role role = roleRepository.findByName("ADMIN").orElseGet(() ->
                roleRepository.save(Role.builder().name("ADMIN").description("admin").active(true).build()));
        User user = userRepository.findByUsername(username).orElseGet(() ->
                userRepository.save(User.builder()
                        .username(username).password("x").realName(username)
                        .roles(Set.of(role)).enabled(true).build()));
        CurrentUserProvider.set(AuthUser.builder()
                .userId(user.getId()).username(username).realName(username)
                .roles(Set.of("ADMIN")).build());
    }

    private OperationLog saveLog(String type, String targetType, Long targetId,
                                 String operator, String details, String result,
                                 LocalDateTime time) {
        return logRepository.save(OperationLog.builder()
                .operationType(type).targetType(targetType).targetId(targetId)
                .operator(operator).details(details).result(result)
                .operationTime(time).build());
    }

    @Test
    void combinedFiltersApplyByIntersection() {
        loginAs("alice");
        LocalDateTime base = LocalDateTime.of(2026, 9, 1, 10, 0);
        saveLog("CREATE", "COURSE", 7L, "alice", "创建课程 #7 Java入门", "SUCCESS", base);
        saveLog("CREATE", "INSTRUCTOR", 8L, "alice", "创建讲师 #8", "SUCCESS", base.plusHours(1));
        saveLog("UPDATE", "COURSE", 7L, "bob", "更新课程 #7 名称", "SUCCESS", base.plusHours(2));
        saveLog("DELETE", "COURSE", 9L, "alice", "删除课程 #9", "SUCCESS", base.plusHours(3));

        // 操作类型 + 目标类型 + 操作人 三个非空条件取交集，而不是只应用其中一个
        var result = logService.search(0, 10, "CREATE", "COURSE", "ali", null, null, null, null, null);
        assertEquals(1, result.getTotalElements());
        assertEquals(7L, result.getContent().get(0).getTargetId());
        assertEquals("创建课程 #7 Java入门", result.getContent().get(0).getDetails());

        // 关键词命中操作详情
        var byDetail = logService.search(0, 10, null, null, null, "Java入门", null, null, null, null);
        assertEquals(1, byDetail.getTotalElements());

        // 关键词命中操作人
        var byOperator = logService.search(0, 10, null, null, null, "ali", null, null, null, null);
        // alice 的 3 条（CREATE COURSE、CREATE INSTRUCTOR、DELETE COURSE）
        assertEquals(3, byOperator.getTotalElements());

        // 纯数字关键词精确命中“明确的目标编号”，而不是模糊匹配详情里的任意数字
        var byTargetId = logService.search(0, 10, null, null, null, "7", null, null, null, null);
        assertEquals(2, byTargetId.getTotalElements());
        assertTrue(byTargetId.getContent().stream().allMatch(l ->
                Long.valueOf(7L).equals(l.getTargetId())
                        || (l.getDetails() != null && l.getDetails().contains("7"))
                        || (l.getOperator() != null && l.getOperator().contains("7"))));

        // 全部四个条件叠加：CREATE + COURSE + alice + 关键词 7
        var all = logService.search(0, 10, "CREATE", "COURSE", "alice", "7", null, null, null, null);
        assertEquals(1, all.getTotalElements());

        // 关键词命中目标编号且与其他条件交集
        var targetAndType = logService.search(0, 10, null, "INSTRUCTOR", null, "8", null, null, null, null);
        assertEquals(1, targetAndType.getTotalElements());
        assertEquals(8L, targetAndType.getContent().get(0).getTargetId());
    }

    @Test
    void singleSidedDateRangeNarrowsResults() {
        loginAs("alice");
        saveLog("CREATE", "COURSE", 1L, "alice", "d1", "SUCCESS", LocalDateTime.of(2026, 9, 1, 9, 0));
        saveLog("CREATE", "COURSE", 2L, "alice", "d2", "SUCCESS", LocalDateTime.of(2026, 9, 5, 9, 0));
        saveLog("CREATE", "COURSE", 3L, "alice", "d3", "SUCCESS", LocalDateTime.of(2026, 9, 10, 9, 0));

        // 只填开始日期
        var onlyStart = logService.search(0, 10, null, null, null, null,
                LocalDate.of(2026, 9, 5), null, null, null);
        assertEquals(2, onlyStart.getTotalElements());

        // 只填结束日期（含当天 23:59:59）
        var onlyEnd = logService.search(0, 10, null, null, null, null,
                null, LocalDate.of(2026, 9, 5), null, null);
        assertEquals(2, onlyEnd.getTotalElements());

        // 同一天起止
        var sameDay = logService.search(0, 10, null, null, null, null,
                LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 5), null, null);
        assertEquals(1, sameDay.getTotalElements());
    }

    @Test
    void endDateBoundaryUsesExclusiveNextMidnight() {
        loginAs("alice");
        // 结束日当天最后一刻 与 次日零点 各一条：上界为次日零点（不含）
        saveLog("CREATE", "COURSE", 1L, "alice", "当天", "SUCCESS",
                LocalDateTime.of(2026, 9, 5, 23, 59, 59));
        saveLog("CREATE", "COURSE", 2L, "alice", "次日零点", "SUCCESS",
                LocalDateTime.of(2026, 9, 6, 0, 0, 0));

        var result = logService.search(0, 10, null, null, null, null,
                null, LocalDate.of(2026, 9, 5), null, null);
        assertEquals(1, result.getTotalElements());
        assertEquals("当天", result.getContent().get(0).getDetails());
    }

    @Test
    void startAfterEndGivesUnderstandableError() {
        loginAs("alice");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                logService.search(0, 10, null, null, null, null,
                        LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 1), null, null));
        assertTrue(ex.getMessage().contains("开始日期不能晚于结束日期"));
    }

    @Test
    void snapshotStaysStableWhileLogsKeepBeingWritten() {
        loginAs("alice");
        LocalDateTime base = LocalDateTime.of(2026, 9, 1, 8, 0);
        for (long i = 1; i <= 15; i++) {
            saveLog("CREATE", "COURSE", i, "alice", "课程 " + i, "SUCCESS", base.plusMinutes(i));
        }

        // 用户执行一次搜索，建立查询视图
        var first = logService.search(0, 10, null, null, null, null, null, null, null, null);
        Long vid = first.getViewId();
        assertTrue(first.isViewCreated());
        assertEquals(15, first.getTotalElements());
        assertEquals(10, first.getContent().size());

        // 用户翻到后续页期间，后台异步写入大量“新的、排在更前面”的匹配日志
        for (long i = 100; i < 130; i++) {
            saveLog("CREATE", "COURSE", i, "alice", "晚到日志 " + i, "SUCCESS", LocalDateTime.now());
        }

        var second = logService.search(1, 10, null, null, null, null, null, null, vid, null);
        assertEquals(15, second.getTotalElements(), "总数必须保持为搜索时的快照总数");
        assertEquals(5, second.getContent().size());

        // 上一页末尾与下一页开头不能重复，原记录不能漏
        var page0 = logService.search(0, 10, null, null, null, null, null, null, vid, null);
        List<Long> idsPage0 = page0.getContent().stream().map(OperationLog::getId).toList();
        List<Long> idsPage1 = second.getContent().stream().map(OperationLog::getId).toList();
        assertEquals(10, idsPage0.stream().distinct().count());
        assertEquals(5, idsPage1.stream().distinct().count());
        assertTrue(idsPage0.stream().noneMatch(idsPage1::contains), "两页之间出现重复记录");

        // 快照内全量仍是原始 15 条，新写入的日志没有进入旧视图
        List<Long> snapshotIds = viewItemRepository.findLogIds(vid);
        assertEquals(15, snapshotIds.size());
        Set<Long> lateIds = logRepository.findAll().stream()
                .filter(l -> l.getDetails() != null && l.getDetails().startsWith("晚到日志"))
                .map(OperationLog::getId).collect(java.util.stream.Collectors.toSet());
        assertTrue(lateIds.size() >= 30);
        assertTrue(snapshotIds.stream().noneMatch(lateIds::contains), "新写入日志进入了旧视图");
    }

    @Test
    void sameTimestampLogsHaveDeterministicOrder() {
        loginAs("alice");
        LocalDateTime same = LocalDateTime.of(2026, 9, 1, 8, 0);
        OperationLog a = saveLog("CREATE", "COURSE", 1L, "alice", "a", "SUCCESS", same);
        OperationLog b = saveLog("CREATE", "COURSE", 2L, "alice", "b", "SUCCESS", same);
        OperationLog c = saveLog("CREATE", "COURSE", 3L, "alice", "c", "SUCCESS", same);

        var r1 = logService.search(0, 10, null, null, null, null, null, null, null, null);
        Long vid = r1.getViewId();
        var r2 = logService.search(0, 10, null, null, null, null, null, null, vid, null);

        List<Long> expected = List.of(c.getId(), b.getId(), a.getId());
        assertEquals(expected, r1.getContent().stream().map(OperationLog::getId).toList());
        assertEquals(expected, r2.getContent().stream().map(OperationLog::getId).toList());
    }

    @Test
    void refreshBuildsNewViewAndCountsNewMatches() {
        loginAs("alice");
        LocalDateTime base = LocalDateTime.of(2026, 9, 1, 8, 0);
        for (long i = 1; i <= 12; i++) {
            saveLog("CREATE", "COURSE", i, "alice", "课程 " + i, "SUCCESS", base.plusMinutes(i));
        }
        // 带筛选建立视图，便于验证刷新后“新增”只统计匹配当前筛选的记录
        var first = logService.search(0, 10, "CREATE", "COURSE", null, null, null, null, null, null);
        assertEquals(12, first.getTotalElements());

        // 后台新写入 3 条匹配日志 + 1 条不匹配日志（目标类型不符）
        saveLog("CREATE", "COURSE", 20L, "alice", "新课程20", "SUCCESS", LocalDateTime.now());
        saveLog("CREATE", "COURSE", 21L, "alice", "新课程21", "SUCCESS", LocalDateTime.now());
        saveLog("CREATE", "INSTRUCTOR", 22L, "bob", "不匹配", "SUCCESS", LocalDateTime.now());
        saveLog("CREATE", "COURSE", 23L, "alice", "新课程23", "SUCCESS", LocalDateTime.now());

        // 用户点击刷新：fromViewId 指向旧视图，新视图按最新数据、相同筛选建立
        var refreshed = logService.search(0, 10, "CREATE", "COURSE", null, null, null, null, null,
                first.getViewId());
        assertTrue(refreshed.isViewCreated());
        assertNotEquals(first.getViewId(), refreshed.getViewId());
        assertEquals(15, refreshed.getTotalElements());
        assertEquals(3L, refreshed.getNewCount(), "应明确看到新增的 3 条匹配记录");
    }

    @Test
    void viewsAreIsolatedBetweenUsers() {
        loginAs("alice");
        saveLog("CREATE", "COURSE", 1L, "alice", "alice-1", "SUCCESS", LocalDateTime.of(2026, 9, 1, 8, 0));
        saveLog("UPDATE", "COURSE", 2L, "alice", "alice-2", "SUCCESS", LocalDateTime.of(2026, 9, 1, 9, 0));
        var aliceView = logService.search(0, 10, "CREATE", null, null, null, null, null, null, null);
        assertEquals(1, aliceView.getTotalElements());

        // 另一名用户使用不同筛选条件
        CurrentUserProvider.clear();
        loginAs("bob");
        saveLog("CREATE", "INSTRUCTOR", 3L, "bob", "bob-1", "SUCCESS", LocalDateTime.now());
        var bobView = logService.search(0, 10, null, "INSTRUCTOR", null, null, null, null, null, null);
        assertEquals(1, bobView.getTotalElements());

        // bob 不能使用 alice 的视图翻页
        assertThrows(jakarta.persistence.EntityNotFoundException.class, () ->
                logService.search(0, 10, null, null, null, null, null, null, aliceView.getViewId(), null));

        // 回到 alice，视图内容与总数仍是她自己的筛选结果，不受 bob 写入影响
        CurrentUserProvider.clear();
        loginAs("alice");
        var alicePage = logService.search(0, 10, "CREATE", null, null, null, null, null,
                aliceView.getViewId(), null);
        assertEquals(1, alicePage.getTotalElements());
        assertEquals("alice-1", alicePage.getContent().get(0).getDetails());
    }

    @Test
    void changingPageSizeStaysWithinSameView() {
        loginAs("alice");
        LocalDateTime base = LocalDateTime.of(2026, 9, 1, 8, 0);
        for (long i = 1; i <= 25; i++) {
            saveLog("CREATE", "COURSE", i, "alice", "课程 " + i, "SUCCESS", base.plusMinutes(i));
        }
        var first = logService.search(0, 10, null, null, null, null, null, null, null, null);
        Long vid = first.getViewId();

        // 每页 20 条，新日志写入
        saveLog("CREATE", "COURSE", 99L, "alice", "晚到的日志", "SUCCESS", LocalDateTime.now());

        var bigger = logService.search(0, 20, null, null, null, null, null, null, vid, null);
        assertFalse(bigger.isViewCreated());
        assertEquals(25, bigger.getTotalElements(), "改变每页条数仍基于同一快照");
        assertEquals(20, bigger.getContent().size());
    }
}
