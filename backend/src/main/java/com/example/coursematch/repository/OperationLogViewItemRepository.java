package com.example.coursematch.repository;

import com.example.coursematch.entity.OperationLog;
import com.example.coursematch.entity.OperationLogViewItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OperationLogViewItemRepository extends JpaRepository<OperationLogViewItem, Long> {

    /**
     * 按快照内确定序号（操作时间倒序、日志 ID 倒序）取当前页日志。
     * rank_no 从 0 开始，区间为 [start, end)。
     */
    @Query("SELECT o FROM OperationLogViewItem i JOIN OperationLog o ON o.id = i.logId "
            + "WHERE i.viewId = :viewId AND i.rankNo >= :start AND i.rankNo < :end "
            + "ORDER BY i.rankNo ASC")
    List<OperationLog> findLogs(@Param("viewId") Long viewId,
                                @Param("start") long start,
                                @Param("end") long end);

    @Query("SELECT i.logId FROM OperationLogViewItem i WHERE i.viewId = :viewId ORDER BY i.rankNo ASC")
    List<Long> findLogIds(@Param("viewId") Long viewId);

    @Query("SELECT i.id FROM OperationLogViewItem i WHERE i.viewId IN :viewIds")
    List<Long> findIdsByViewIds(@Param("viewIds") List<Long> viewIds);

    @Modifying
    @Query("DELETE FROM OperationLogViewItem i WHERE i.id IN :ids")
    void deleteAllByIdIn(@Param("ids") List<Long> ids);

    /**
     * 把当前筛选匹配的日志一次性写入快照，序号按 operation_time DESC, id DESC 确定。
     * 条件与 {@link OperationLogSpecs} 保持一致；用一条 INSERT...SELECT 保证视图
     * 建立在同一读时刻上，建立后即便持续有日志写入也不会影响该视图。
     */
    @Modifying
    @Query(value = "INSERT INTO operation_log_view_item (view_id, log_id, rank_no) "
            + "SELECT :viewId, l.id, "
            + "(ROW_NUMBER() OVER (ORDER BY l.operation_time DESC, l.id DESC) - 1) "
            + "FROM operation_logs l WHERE 1=1 "
            + "AND (:operationType IS NULL OR l.operation_type = :operationType) "
            + "AND (:targetType IS NULL OR l.target_type = :targetType) "
            + "AND (:operator IS NULL OR l.operator LIKE CONCAT('%', :operator, '%')) "
            + "AND (:startTime IS NULL OR l.operation_time >= :startTime) "
            + "AND (:endTime IS NULL OR l.operation_time < :endTime) "
            + "AND (:keyword IS NULL OR "
            + "     l.operator LIKE CONCAT('%', :keyword, '%') "
            + "  OR l.details LIKE CONCAT('%', :keyword, '%') "
            + "  OR (:keywordNumeric = TRUE AND l.target_id = :keywordLong))",
            nativeQuery = true)
    int copyFiltered(@Param("viewId") Long viewId,
                     @Param("operationType") String operationType,
                     @Param("targetType") String targetType,
                     @Param("operator") String operator,
                     @Param("keyword") String keyword,
                     @Param("keywordLong") long keywordLong,
                     @Param("keywordNumeric") boolean keywordNumeric,
                     @Param("startTime") LocalDateTime startTime,
                     @Param("endTime") LocalDateTime endTime);
}
