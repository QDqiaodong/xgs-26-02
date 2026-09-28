package com.example.coursematch.service;

import com.example.coursematch.dto.response.LogPageResponse;
import com.example.coursematch.entity.OperationLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;

public interface OperationLogService {

    void log(String operationType, String targetType, Long targetId, String details);

    void log(String operationType, String targetType, Long targetId, String operator, String ipAddress, String details, String result);

    Page<OperationLog> findByOperationType(String operationType, Pageable pageable);

    Page<OperationLog> findByTargetType(String targetType, Pageable pageable);

    Page<OperationLog> findByOperator(String operator, Pageable pageable);

    Page<OperationLog> findByTimeRange(LocalDateTime startTime, LocalDateTime endTime, Pageable pageable);

    /**
     * 按组合条件分页查询操作日志，基于查询视图保证一次搜索会话内结果稳定。
     *
     * @param page       零基页码
     * @param size       每页条数
     * @param viewId     已有视图 ID：翻页时回传，复用同一份快照；为空则新建视图
     * @param fromViewId 刷新前的旧视图 ID：用于计算新增匹配条数；仅在新建视图时有意义
     */
    LogPageResponse search(int page, int size,
                           String operationType, String targetType, String operator,
                           String keyword, LocalDate startDate, LocalDate endDate,
                           Long viewId, Long fromViewId);
}
