package com.conca.ontology.agent.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.conca.ontology.agent.config.HarnessAgentFactory;
import com.conca.ontology.shared.web.ApiException;

import io.agentscope.harness.agent.HarnessAgent;
import jakarta.annotation.PreDestroy;

/**
 * 模型级智能体实例注册表（chat 页「模型切换」用）：按 OpenRouter slug 缓存
 * HarnessAgent 实例（HarnessAgentFactory.build），与主智能体单例并存。
 *
 * - 惰性构建：首次以某 slug 对话时装配，按 slug 互斥（避免并发建双实例）；
 * - 软上限 LRU：超上限时逐出 inUse==0 且最久未访问的实例（全部进行中则暂缓，绝不影响会话）；
 * - 生命周期：实例 close 随本注册表统一管理（模型连接与内存随实例释放）；
 * - 会话连续性：实例与主智能体同名同工作区同状态存储，仅 modelName 不同，
 *   切换模型后（含切回默认）对话与记忆连续。
 *
 * @author tianbaoxing
 * @date 2026-10-30
 */
@Service
public class ModelAgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelAgentRegistry.class);

    private static final class Entry {
        final HarnessAgent agent;
        final AtomicInteger inUse = new AtomicInteger();
        final AtomicLong lastAccess = new AtomicLong(System.currentTimeMillis());

        Entry(HarnessAgent agent) {
            this.agent = agent;
        }
    }

    private final HarnessAgentFactory factory;
    private final int maxAgents;

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public ModelAgentRegistry(HarnessAgentFactory factory,
            @Value("${app.agent.model-agents-max:3}") int maxAgents) {
        this.factory = factory;
        this.maxAgents = Math.max(1, maxAgents);
    }

    /** 取可用实例（无则按 slug 装配）；调用方必须在流结束后 release（LRU 驱逐的进行中保护） */
    public HarnessAgent acquire(String slug) {
        Entry entry = cache.get(slug);
        if (entry == null) {
            synchronized (lockFor(slug)) {
                entry = cache.get(slug);
                if (entry == null) {
                    entry = new Entry(build(slug));
                    cache.put(slug, entry);
                }
            }
        }
        entry.inUse.incrementAndGet();
        entry.lastAccess.set(System.currentTimeMillis());
        evictOverflow();
        return entry.agent;
    }

    /** 归还（chat 流 doFinally 调用） */
    public void release(String slug) {
        Entry entry = cache.get(slug);
        if (entry != null) {
            entry.inUse.decrementAndGet();
            entry.lastAccess.set(System.currentTimeMillis());
        }
    }

    @PreDestroy
    public void shutdown() {
        cache.values().forEach(ModelAgentRegistry::closeQuietly);
        cache.clear();
        log.info("模型级智能体实例已全部关闭");
    }

    // ---------------- 内部 ----------------

    private HarnessAgent build(String slug) {
        try {
            return factory.build(slug);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(503, "模型级智能体装配失败（" + slug + "）：" + e.getMessage());
        }
    }

    /** 软上限 LRU：超上限时逐出 inUse==0 且最久未访问的实例（先计自身在用，绝不开除自己） */
    private void evictOverflow() {
        while (cache.size() > maxAgents) {
            List<Map.Entry<String, Entry>> idle = cache.entrySet().stream()
                    .filter(e -> e.getValue().inUse.get() == 0)
                    .sorted(Comparator.comparingLong(e -> e.getValue().lastAccess.get()))
                    .toList();
            if (idle.isEmpty()) {
                break; // 全部进行中：暂缓驱逐，等 release 后下次 acquire 再清
            }
            Map.Entry<String, Entry> victim = idle.get(0);
            if (cache.remove(victim.getKey(), victim.getValue())) {
                closeQuietly(victim.getValue());
                log.info("模型级智能体实例 LRU 驱逐：{}（当前 {} 个）", victim.getKey(), cache.size());
            }
        }
    }

    private static void closeQuietly(Entry entry) {
        try {
            entry.agent.close();
        } catch (Exception e) {
            log.warn("模型级智能体实例关闭异常：{}", e.getMessage());
        }
    }

    private Object lockFor(String slug) {
        return locks.computeIfAbsent(slug, k -> new Object());
    }
}
