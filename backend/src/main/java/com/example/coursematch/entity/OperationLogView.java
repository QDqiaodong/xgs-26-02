package com.example.coursematch.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 操作日志的一次“查询视图”：用户点击搜索或刷新时，按当时的筛选条件
 * 把匹配到的日志 ID 快照固化下来。
 *
 * <p>同一次搜索会话内翻页都基于该快照，日志持续写入不会把结果集顶动，
 * 不会出现上一页末尾在下一页重复、原有记录漏掉、总数忽上忽下。
 * 用户主动刷新或重新搜索才会建立新视图。
 *
 * <p>视图按登录用户隔离，两名用户使用不同筛选条件互不影响。
 */
@Entity
@Table(name = "operation_log_view",
        indexes = {
                @Index(name = "idx_log_view_owner", columnList = "owner_user_id,created_at")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OperationLogView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 建立该视图的登录用户；未登录（兼容场景）为 null */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "operation_type", length = 50)
    private String operationType;

    @Column(name = "target_type", length = 50)
    private String targetType;

    @Column(name = "operator", length = 50)
    private String operator;

    @Column(length = 100)
    private String keyword;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    /** 快照固化时的匹配总数，整个搜索会话内保持不变 */
    @Column(name = "total_elements", nullable = false)
    private long totalElements;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
