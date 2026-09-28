package com.example.coursematch.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 操作日志查询视图存储。
 *
 * <p>用户每次搜索/刷新都会建立一个独立的“查询视图”：视图固化当时的筛选条件指纹、
 * 日志 ID 高水位（maxId）与总数。之后翻页、改变每页条数都在同一视图内进行，
 * 后台异步新写入的匹配日志既不会顶动结果集，也不会改变总数；
 * 用户主动刷新或重新搜索才建立新视图，看到新增记录和更新后的总数。</p>
 *
 * <p>视图按登录用户隔离：匿名访问使用 null 主体，两名用户即使筛选条件相同也互不可见。</p>
 */
@Slf4j
@Component
public class LogQueryViewStore {

    /** 视图空闲存活时间：每次访问滑动续期 */
    private static final Duration TTL = Duration.ofMinutes(30);

    public record View(String token, String owner, String fingerprint, long maxId,
                       long totalElements, LocalDateTime createdAt, LocalDateTime lastAccessAt) {

        private View touch() {
            return new View(token, owner, fingerprint, maxId, totalElements, createdAt, LocalDateTime.now());
        }
    }

    private final Map<String, View> views = new ConcurrentHashMap<>();

    /** 建立新视图。owner 为登录用户名（匿名为 null），fingerprint 为归一化后的筛选条件指纹 */
    public View create(String owner, String fingerprint, long maxId, long totalElements) {
        evictExpired();
        LocalDateTime now = LocalDateTime.now();
        String token = UUID.randomUUID().toString().replace("-", "");
        View view = new View(token, owner, fingerprint, maxId, totalElements, now, now);
        views.put(token, view);
        return view;
    }

    /**
     * 按令牌解析视图。仅同一主体可取用；不存在、已过期或归属其他用户时返回空，
     * 调用方应视为需要建立新视图（例如重新登录或长时间离开后）。
     */
    public Optional<View> resolve(String token, String owner) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        View view = views.get(token);
        if (view == null || view.lastAccessAt().plus(TTL).isBefore(LocalDateTime.now())) {
            if (view != null) {
                views.remove(token);
            }
            return Optional.empty();
        }
        if (!ownerEquals(view.owner(), owner)) {
            return Optional.empty();
        }
        View touched = view.touch();
        views.put(token, touched);
        return Optional.of(touched);
    }

    public void remove(String token) {
        if (token != null) {
            views.remove(token);
        }
    }

    private static boolean ownerEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private void evictExpired() {
        Iterator<Map.Entry<String, View>> it = views.entrySet().iterator();
        LocalDateTime now = LocalDateTime.now();
        while (it.hasNext()) {
            View v = it.next().getValue();
            if (v.lastAccessAt().plus(TTL).isBefore(now)) {
                it.remove();
            }
        }
    }
}
