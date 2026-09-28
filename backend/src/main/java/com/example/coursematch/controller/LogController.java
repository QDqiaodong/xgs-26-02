package com.example.coursematch.controller;

import com.example.coursematch.dto.response.ApiResponse;
import com.example.coursematch.dto.response.LogPageResponse;
import com.example.coursematch.entity.OperationLog;
import com.example.coursematch.repository.OperationLogRepository;
import com.example.coursematch.security.CurrentUserProvider;
import com.example.coursematch.service.LogQueryParams;
import com.example.coursematch.service.OperationLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "操作日志", description = "系统操作日志查询接口")
public class LogController {

    private static final int MAX_PAGE_SIZE = 200;

    private final OperationLogRepository operationLogRepository;
    private final OperationLogService operationLogService;

    @GetMapping
    @Operation(summary = "分页查询操作日志",
            description = "操作类型、目标类型、操作人、关键词（操作人/操作详情/目标编号）与时间范围按交集筛选；"
                    + "同一查询视图（viewToken）内结果集与总数不受新写入日志影响")
    public ApiResponse<LogPageResponse> getLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate endDate,
            @RequestParam(required = false) String viewToken,
            @RequestParam(required = false) String previousToken) {

        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            // 给出可理解的提示，而不是返回一页看似正常的空数据
            throw new IllegalArgumentException("开始日期不能晚于结束日期，请调整日期范围后重新查询");
        }

        int pageIndex = Math.max(0, page);
        int pageSize = size <= 0 ? 10 : Math.min(size, MAX_PAGE_SIZE);

        LocalDateTime startTime = startDate == null ? null : startDate.atStartOfDay();
        LocalDateTime endTime = endDate == null ? null : endDate.atTime(LocalTime.MAX);

        LogQueryParams params = new LogQueryParams(
                trimToNull(operationType),
                trimToNull(targetType),
                trimToNull(operator),
                trimToNull(keyword),
                startTime,
                endTime);

        Pageable pageable = PageRequest.of(pageIndex, pageSize);
        String owner = CurrentUserProvider.get().map(u -> u.getUsername()).orElse(null);

        LogPageResponse result = operationLogService.search(
                params, pageable, trimToNull(viewToken), owner, trimToNull(previousToken));
        return ApiResponse.success(result);
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询日志详情", description = "根据日志ID查询详细信息")
    public ApiResponse<OperationLog> getLogById(@PathVariable Long id) {
        return operationLogRepository.findById(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "日志不存在"));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
