package com.example.coursematch.service.impl;

import com.example.coursematch.dto.response.LogPageResponse;
import com.example.coursematch.entity.OperationLog;
import com.example.coursematch.repository.OperationLogRepository;
import com.example.coursematch.service.LogQueryParams;
import com.example.coursematch.service.LogQueryViewStore;
import com.example.coursematch.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class OperationLogServiceImpl implements OperationLogService {

    /** 操作时间相同时以 ID 倒序兜底，保证同一时刻多条日志仍有确定先后 */
    private static final Sort STABLE_SORT =
            Sort.by(Sort.Order.desc("operationTime"), Sort.Order.desc("id"));

    private final OperationLogRepository operationLogRepository;
    private final LogQueryViewStore viewStore;

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
    public LogPageResponse search(LogQueryParams params, Pageable requested, String viewToken,
                                  String owner, String previousToken) {
        String fingerprint = params.fingerprint();

        // 复用视图的三个必要条件：令牌有效、归属当前用户、筛选条件指纹一致。
        // 任一不满足（刷新时前端不传令牌、改条件后重新搜索、重新登录、视图过期）
        // 都建立新视图，确保展示的结果始终与筛选含义一致。
        LogQueryViewStore.View view = viewStore.resolve(viewToken, owner)
                .filter(v -> v.fingerprint().equals(fingerprint))
                .orElse(null);
        boolean freshView = view == null;

        long newSinceView = 0;
        if (freshView) {
            // 新视图：以当前最大日志 ID 作为高水位，之后写入的日志不进入本视图
            long maxId = operationLogRepository.findMaxId();
            long total = operationLogRepository.countSearch(
                    params.operationType(), params.targetType(), params.operator(),
                    params.keyword(), parseKeywordTargetId(params.keyword()),
                    params.startTime(), params.endTime(), maxId);
            if (previousToken != null && !previousToken.isBlank()) {
                newSinceView = Math.max(0, total
                        - viewStore.resolve(previousToken, owner).map(LogQueryViewStore.View::totalElements).orElse(total));
            }
            view = viewStore.create(owner, fingerprint, maxId, total);
        }

        // 改变每页条数时由前端回到第 0 页；服务端同样对越界页码做兜底
        int page = Math.max(0, requested.getPageNumber());
        int size = Math.max(1, requested.getPageSize());
        Pageable pageable = PageRequest.of(page, size, STABLE_SORT);
        Page<OperationLog> result = operationLogRepository.search(
                params.operationType(), params.targetType(), params.operator(),
                params.keyword(), parseKeywordTargetId(params.keyword()),
                params.startTime(), params.endTime(), view.maxId(), pageable);

        // 总数直接取视图固化值，而不是本次 COUNT(*) 结果，防止总数忽上忽下
        long stableTotal = view.totalElements();
        int totalPages = size == 0 ? 0 : (int) ((stableTotal + size - 1) / size);

        return LogPageResponse.builder()
                .content(result.getContent())
                .totalElements(stableTotal)
                .totalPages(totalPages)
                .currentPage(page)
                .pageSize(size)
                .viewToken(view.token())
                .viewCreatedAt(view.createdAt())
                .newSinceView(freshView ? newSinceView : 0)
                .build();
    }

    /** 关键词为纯整数时额外按目标编号精确匹配，否则只参与文本匹配 */
    private Long parseKeywordTargetId(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(keyword.trim());
            return value >= 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
