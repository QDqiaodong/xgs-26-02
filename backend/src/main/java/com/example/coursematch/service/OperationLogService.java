package com.example.coursematch.service;

import com.example.coursematch.dto.response.LogPageResponse;
import org.springframework.data.domain.Pageable;

public interface OperationLogService {

    void log(String operationType, String targetType, Long targetId, String details);

    void log(String operationType, String targetType, Long targetId, String operator, String ipAddress, String details, String result);

    /**
     * 组合条件分页检索操作日志。
     * <p>所有非空条件按交集生效；关键词覆盖操作人、操作详情、目标编号；
     * 通过查询视图（viewToken）保证一次搜索会话内结果集与总数稳定，
     * 后台持续写入的新日志只在刷新或重新搜索建立新视图后才出现。</p>
     *
     * @param params    归一化后的筛选条件
     * @param pageable  分页与排序（服务内部固定 operationTime DESC, id DESC）
     * @param viewToken 已有视图令牌；为空或与当前用户/筛选条件不匹配时建立新视图
     * @param owner     当前登录用户名（匿名为 null），用于视图隔离
     * @param previousToken 前端上次持有的视图令牌（仅用于计算新增条数提示）
     */
    LogPageResponse search(LogQueryParams params, Pageable pageable, String viewToken,
                           String owner, String previousToken);
}
