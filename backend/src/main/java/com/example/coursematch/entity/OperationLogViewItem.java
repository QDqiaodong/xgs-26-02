package com.example.coursematch.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 查询视图中的单条日志快照（视图 ID + 日志 ID）。
 *
 * <p>rank_no 为建立视图时的确定序号：按 operation_time DESC、id DESC 排序，
 * 同一时刻产生多条日志时先后顺序仍然确定。翻页直接对 rank_no 做区间截取，
 * 新写入的日志没有进入旧视图，结果集在整个搜索会话内不会漂移。
 */
@Entity
@Table(name = "operation_log_view_item",
        indexes = {
                @Index(name = "idx_log_view_item_view", columnList = "view_id,rank_no", unique = true)
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OperationLogViewItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "view_id", nullable = false)
    private Long viewId;

    @Column(name = "log_id", nullable = false)
    private Long logId;

    @Column(name = "rank_no", nullable = false)
    private long rankNo;
}
