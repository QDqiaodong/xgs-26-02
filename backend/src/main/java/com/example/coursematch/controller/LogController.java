package com.example.coursematch.controller;

import com.example.coursematch.dto.response.ApiResponse;
import com.example.coursematch.dto.response.LogPageResponse;
import com.example.coursematch.entity.OperationLog;
import com.example.coursematch.repository.OperationLogRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "操作日志", description = "系统操作日志查询接口")
public class LogController {

    private final OperationLogRepository operationLogRepository;
    private final com.example.coursematch.service.OperationLogService operationLogService;

    @GetMapping
    @Operation(summary = "分页查询操作日志",
            description = "操作类型、目标类型、操作人、关键词、时间范围按交集筛选；关键词命中操作人、"
                    + "操作详情或明确的目标编号。结果基于查询视图快照：一次搜索会话内翻页稳定，"
                    + "新写入日志不会顶动结果；刷新/重新搜索才建立新视图。")
    public ApiResponse<LogPageResponse> getLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate endDate,
            @RequestParam(required = false) Long viewId,
            @RequestParam(required = false) Long fromViewId) {

        LogPageResponse result = operationLogService.search(page, size,
                operationType, targetType, operator, keyword, startDate, endDate,
                viewId, fromViewId);
        return ApiResponse.success(result);
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询日志详情", description = "根据日志ID查询详细信息；点击列表行时按该行 ID 打开，不受新日志写入影响")
    public ApiResponse<OperationLog> getLogById(@PathVariable Long id) {
        return operationLogRepository.findById(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "日志不存在"));
    }
}
