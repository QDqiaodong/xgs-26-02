package com.example.coursematch.dto.response;

import com.example.coursematch.entity.OperationLog;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 操作日志分页结果。
 *
 * <p>基于“查询视图”（快照）返回：同一次搜索会话内 content/totalElements 稳定，
 * 新写入的日志不会顶动结果。用户刷新建立新视图时，newCount 为相对旧视图
 * 新增且匹配当前筛选的日志条数。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogPageResponse {
    private List<OperationLog> content;
    private long totalElements;
    private int totalPages;
    private int currentPage;
    private int pageSize;
    /** 本次结果所属的查询视图 ID；翻页时回传以复用同一份快照 */
    private Long viewId;
    /** 是否为本次请求新建的视图（搜索/刷新为 true，翻页为 false） */
    private boolean viewCreated;
    /** 刷新时相对上一视图新增的匹配日志条数；非刷新场景为 null */
    private Long newCount;
}
