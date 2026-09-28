package com.example.coursematch.service;

import java.time.LocalDateTime;

/**
 * 日志检索的归一化筛选条件（空字符串已转为 null）。
 */
public record LogQueryParams(String operationType,
                             String targetType,
                             String operator,
                             String keyword,
                             LocalDateTime startTime,
                             LocalDateTime endTime) {

    /**
     * 生成筛选条件指纹。视图令牌只允许在指纹一致的请求间复用，
     * 改动任一筛选条件都会落入新视图，杜绝“条件变了却沿用旧结果集”。
     */
    public String fingerprint() {
        return String.join("|",
                nz(operationType), nz(targetType), nz(operator), nz(keyword),
                startTime == null ? "" : startTime.toString(),
                endTime == null ? "" : endTime.toString());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
