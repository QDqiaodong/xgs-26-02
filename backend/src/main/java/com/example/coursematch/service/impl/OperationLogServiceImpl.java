package com.example.coursematch.service.impl;

import com.example.coursematch.dto.response.LogPageResponse;
import com.example.coursematch.entity.OperationLog;
import com.example.coursematch.entity.OperationLogView;
import com.example.coursematch.repository.OperationLogRepository;
import com.example.coursematch.repository.OperationLogViewItemRepository;
import com.example.coursematch.repository.OperationLogViewRepository;
import com.example.coursematch.security.AuthUser;
import com.example.coursematch.security.CurrentUserProvider;
import com.example.coursematch.service.OperationLogService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class OperationLogServiceImpl implements OperationLogService {

    /** 视图最长保留时间，过期由定时任务清理 */
    private static final long VIEW_TTL_HOURS = 6;
    /** 每个用户最多保留的视图数量，超出时裁剪最旧的 */
    private static final int MAX_VIEWS_PER_OWNER = 20;
    /** 清理快照项时每批删除的条数，避免 IN 列表过大 */
    private static final int DELETE_BATCH = 1000;
    private static final int MAX_PAGE_SIZE = 200;

    private final OperationLogRepository operationLogRepository;
    private final OperationLogViewRepository viewRepository;
    private final OperationLogViewItemRepository viewItemRepository;
    private final EntityManager entityManager;

    @Async
    @Override
    public void log(String operationType, String targetType, Long targetId, String details) {
        log(operationType, targetType, targetId, "system", null, details, "SUCCESS");
    }

    @Async
    @Override
    public void log(String operationType, String targetType, Long targetId, String operator, String ipAddress, String details, String result) {
        try {
            OperationLog operationLog = OperationLog.builder()
                    .operationType(operationType)
                    .targetType(targetType)
                    .targetId(targetId)
                    .operator(operator)
                    .ipAddress(ipAddress)
                    .operationTime(LocalDateTime.now())
                    .details(details)
                    .result(result)
                    .build();
            operationLogRepository.save(operationLog);
        } catch (Exception e) {
            log.error("Failed to save operation log: {}", e.getMessage());
        }
    }

    @Override
    public Page<OperationLog> findByOperationType(String operationType, Pageable pageable) {
        return operationLogRepository.findByOperationType(operationType, pageable);
    }

    @Override
    public Page<OperationLog> findByTargetType(String targetType, Pageable pageable) {
        return operationLogRepository.findByTargetType(targetType, pageable);
    }

    @Override
    public Page<OperationLog> findByOperator(String operator, Pageable pageable) {
        return operationLogRepository.findByOperator(operator, pageable);
    }

    @Override
    public Page<OperationLog> findByTimeRange(LocalDateTime startTime, LocalDateTime endTime, Pageable pageable) {
        return operationLogRepository.findByTimeRange(startTime, endTime, pageable);
    }

    @Override
    @Transactional
    public LogPageResponse search(int requestedPage, int requestedSize,
                                  String operationType, String targetType, String operator,
                                  String keyword, LocalDate startDate, LocalDate endDate,
                                  Long viewId, Long fromViewId) {
        AuthUser currentUser = CurrentUserProvider.require();
        Long ownerId = currentUser.getUserId();

        int size = normalizeSize(requestedSize);

        // 单边日期也要收窄；开始晚于结束直接给出可理解的提示，而不是返回一页看似正常的空数据
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期，请调整查询时间范围后重试");
        }

        boolean createNew = viewId == null;
        OperationLogView view;
        Long newCount = null;

        if (createNew) {
            view = createView(ownerId, operationType, targetType, operator, keyword, startDate, endDate);
            if (fromViewId != null) {
                newCount = countNewMatches(fromViewId, view.getId(), ownerId);
            }
            pruneOldViews(ownerId);
        } else {
            view = getOwnedView(viewId, ownerId);
        }

        long total = view.getTotalElements();
        int totalPages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        // 快照存在期间页码超出范围（例如旧链接）时落到最后一页，而不是漂移到新数据
        int page = Math.max(0, Math.min(requestedPage, Math.max(0, totalPages - 1)));

        long start = (long) page * size;
        long end = Math.min(start + size, total);
        List<OperationLog> content = start >= total
                ? List.of()
                : viewItemRepository.findLogs(view.getId(), start, end);

        return LogPageResponse.builder()
                .content(content)
                .totalElements(total)
                .totalPages(totalPages)
                .currentPage(page)
                .pageSize(size)
                .viewId(view.getId())
                .viewCreated(createNew)
                .newCount(newCount)
                .build();
    }

    private OperationLogView createView(Long ownerId,
                                        String operationType, String targetType, String operator,
                                        String keyword, LocalDate startDate, LocalDate endDate) {
        String opType = trimToNull(operationType);
        String tgtType = trimToNull(targetType);
        String op = trimToNull(operator);
        String kw = trimToNull(keyword);
        LocalDateTime startTime = startDate == null ? null : startDate.atStartOfDay();
        // 结束日期用“次日零点（不含）”做上界：既覆盖当天全部时刻，
        // 也避免 MySQL DATETIME 无小数精度时 23:59:59.999... 进位到次日零点
        LocalDateTime endTime = endDate == null ? null : endDate.plusDays(1).atStartOfDay();

        OperationLogView view = OperationLogView.builder()
                .ownerUserId(ownerId)
                .operationType(opType)
                .targetType(tgtType)
                .operator(op)
                .keyword(kw)
                .startDate(startDate)
                .endDate(endDate)
                .totalElements(0)
                .build();
        view = viewRepository.saveAndFlush(view);

        long keywordLong = 0L;
        boolean keywordNumeric = false;
        if (kw != null) {
            try {
                keywordLong = Long.parseLong(kw);
                keywordNumeric = true;
            } catch (NumberFormatException ignored) {
                // 非纯数字关键词不参与目标编号精确匹配
            }
        }

        // 一条 INSERT...SELECT 把匹配日志固化进快照：同一读时刻、确定顺序
        // （operation_time DESC, id DESC），此后新日志写入不再影响该视图
        int matched = viewItemRepository.copyFiltered(view.getId(), opType, tgtType, op, kw,
                keywordLong, keywordNumeric, startTime, endTime);
        entityManager.flush();
        view.setTotalElements(matched);
        return viewRepository.save(view);
    }

    private OperationLogView getOwnedView(Long viewId, Long ownerId) {
        OperationLogView view = viewRepository.findById(viewId)
                .orElseThrow(() -> new EntityNotFoundException("查询视图不存在或已过期，请重新搜索"));
        // 视图按登录用户隔离：两名用户的筛选条件与结果互不影响
        if (!Objects.equals(view.getOwnerUserId(), ownerId)) {
            throw new EntityNotFoundException("查询视图不存在或已过期，请重新搜索");
        }
        return view;
    }

    /**
     * 刷新时计算新视图相对旧视图“新增”的匹配日志：
     * 新快照中存在、旧快照中不存在的日志条数（被翻页的用户只关心新增，不关心消失项）。
     */
    private Long countNewMatches(Long oldViewId, Long newViewId, Long ownerId) {
        OperationLogView oldView = viewRepository.findById(oldViewId).orElse(null);
        if (oldView == null || !Objects.equals(oldView.getOwnerUserId(), ownerId)) {
            return null;
        }
        Set<Long> oldIds = new HashSet<>(viewItemRepository.findLogIds(oldViewId));
        List<Long> newIds = viewItemRepository.findLogIds(newViewId);
        long added = 0;
        for (Long id : newIds) {
            if (!oldIds.contains(id)) {
                added++;
            }
        }
        return added;
    }

    /** 裁剪当前用户最旧的视图（连同快照项一起删除），避免视图无限累积 */
    private void pruneOldViews(Long ownerId) {
        // 新建视图后用户至多保留最近 MAX_VIEWS_PER_OWNER 个，取出多出来的最旧视图删除
        List<Long> ordered = viewRepository.findRecentViewIds(
                ownerId, PageRequest.of(0, MAX_VIEWS_PER_OWNER + 1));
        if (ordered.size() <= MAX_VIEWS_PER_OWNER) {
            return;
        }
        // 倒序删除（最旧的在末尾）
        for (int i = ordered.size() - 1; i >= MAX_VIEWS_PER_OWNER; i--) {
            deleteView(ordered.get(i));
        }
    }

    private void deleteView(Long viewId) {
        List<Long> itemIds = viewItemRepository.findIdsByViewIds(List.of(viewId));
        for (int i = 0; i < itemIds.size(); i += DELETE_BATCH) {
            viewItemRepository.deleteAllByIdIn(itemIds.subList(i, Math.min(i + DELETE_BATCH, itemIds.size())));
        }
        viewRepository.deleteById(viewId);
        entityManager.flush();
    }

    /** 定时清理超过 TTL 的视图，即使用户建立视图后再也没有访问 */
    @Scheduled(fixedDelay = 60 * 60 * 1000L)
    @Transactional
    public void cleanupExpiredViews() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(VIEW_TTL_HOURS);
        List<OperationLogView> expired = viewRepository.findByCreatedAtBefore(cutoff);
        for (OperationLogView view : expired) {
            try {
                deleteView(view.getId());
            } catch (Exception ex) {
                log.warn("Failed to cleanup operation log view {}: {}", view.getId(), ex.getMessage());
            }
        }
    }

    private int normalizeSize(int size) {
        if (size <= 0) {
            return 10;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
