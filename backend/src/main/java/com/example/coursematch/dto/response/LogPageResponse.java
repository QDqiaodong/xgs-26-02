package com.example.coursematch.dto.response;

import com.example.coursematch.entity.OperationLog;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 操作日志分页响应：携带本次查询视图令牌与视图信息，
 * 前端翻页/改变每页条数时回传视图令牌即可复用固化后的结果集。
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

    /** 查询视图令牌：一次搜索会话内翻页复用，刷新/重新搜索后由服务端换新 */
    private String viewToken;
    /** 视图建立时间（本次搜索/刷新的时刻） */
    private LocalDateTime viewCreatedAt;
    /**
     * 本次刷新建立新视图时，相比上一个视图新增的匹配记录数（仅新视图首屏有意义）。
     * 视图内翻页恒为 0。
     */
    private long newSinceView;
}
