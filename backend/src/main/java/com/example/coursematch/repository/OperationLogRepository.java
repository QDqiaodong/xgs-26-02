package com.example.coursematch.repository;

import com.example.coursematch.entity.OperationLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OperationLogRepository extends JpaRepository<OperationLog, Long> {

    /**
     * 组合检索：所有非空条件按交集（AND）生效。
     * 关键词同时匹配操作人、操作详情和明确的目标编号；
     * 时间范围允许只填开始或只填结束；
     * maxId 为一次查询视图建立时的日志 ID 高水位，视图内不纳入之后新写入的日志。
     * 排序固定为操作时间倒序 + ID 倒序，保证同一时刻多条日志也有确定先后。
     */
    @Query(value = """
            SELECT o FROM OperationLog o
            WHERE (:operationType IS NULL OR o.operationType = :operationType)
              AND (:targetType IS NULL OR o.targetType = :targetType)
              AND (:operator IS NULL OR LOWER(o.operator) LIKE LOWER(CONCAT('%', :operator, '%')))
              AND (:keyword IS NULL
                   OR LOWER(o.operator) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(o.details) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR (o.targetId IS NOT NULL AND o.targetId = :keywordTargetId))
              AND (:startTime IS NULL OR o.operationTime >= :startTime)
              AND (:endTime IS NULL OR o.operationTime <= :endTime)
              AND o.id <= :maxId
            """,
            countQuery = """
            SELECT COUNT(o) FROM OperationLog o
            WHERE (:operationType IS NULL OR o.operationType = :operationType)
              AND (:targetType IS NULL OR o.targetType = :targetType)
              AND (:operator IS NULL OR LOWER(o.operator) LIKE LOWER(CONCAT('%', :operator, '%')))
              AND (:keyword IS NULL
                   OR LOWER(o.operator) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(o.details) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR (o.targetId IS NOT NULL AND o.targetId = :keywordTargetId))
              AND (:startTime IS NULL OR o.operationTime >= :startTime)
              AND (:endTime IS NULL OR o.operationTime <= :endTime)
              AND o.id <= :maxId
            """)
    Page<OperationLog> search(@Param("operationType") String operationType,
                              @Param("targetType") String targetType,
                              @Param("operator") String operator,
                              @Param("keyword") String keyword,
                              @Param("keywordTargetId") Long keywordTargetId,
                              @Param("startTime") LocalDateTime startTime,
                              @Param("endTime") LocalDateTime endTime,
                              @Param("maxId") long maxId,
                              Pageable pageable);

    /** 查询视图建立时刻的日志 ID 高水位 */
    @Query("SELECT COALESCE(MAX(o.id), 0) FROM OperationLog o")
    long findMaxId();

    /** 仅统计符合组合条件的记录数（用于建立查询视图时固化总数，不白取一页数据） */
    @Query("""
            SELECT COUNT(o) FROM OperationLog o
            WHERE (:operationType IS NULL OR o.operationType = :operationType)
              AND (:targetType IS NULL OR o.targetType = :targetType)
              AND (:operator IS NULL OR LOWER(o.operator) LIKE LOWER(CONCAT('%', :operator, '%')))
              AND (:keyword IS NULL
                   OR LOWER(o.operator) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(o.details) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR (o.targetId IS NOT NULL AND o.targetId = :keywordTargetId))
              AND (:startTime IS NULL OR o.operationTime >= :startTime)
              AND (:endTime IS NULL OR o.operationTime <= :endTime)
              AND o.id <= :maxId
            """)
    long countSearch(@Param("operationType") String operationType,
                     @Param("targetType") String targetType,
                     @Param("operator") String operator,
                     @Param("keyword") String keyword,
                     @Param("keywordTargetId") Long keywordTargetId,
                     @Param("startTime") LocalDateTime startTime,
                     @Param("endTime") LocalDateTime endTime,
                     @Param("maxId") long maxId);

    @Query("SELECT o FROM OperationLog o WHERE o.targetType = :targetType AND o.targetId = :targetId")
    List<OperationLog> findByTarget(@Param("targetType") String targetType, @Param("targetId") Long targetId);
}
