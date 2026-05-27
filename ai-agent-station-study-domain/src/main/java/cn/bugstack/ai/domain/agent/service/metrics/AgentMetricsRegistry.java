package cn.bugstack.ai.domain.agent.service.metrics;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI Agent 全局可观测指标注册中心
 * <p>
 * 同时维护：
 * 1. 全局累计指标（自服务启动起）—— 用于看板/简历量化指标统计
 * 2. per-session 指标 —— 用于单次任务的细粒度分析与回放
 * <p>
 * 设计要点：
 * - 所有计数器使用 AtomicLong / ConcurrentHashMap，无锁保证线程安全；
 * - 所有 record* 方法对 sessionId == null 容忍（仅计入全局）；
 * - 所有埋点为 best-effort，不抛异常，不阻塞主链路。
 */
@Component
public class AgentMetricsRegistry {

    // ── 全局计数 ────────────────────────────────────────────────────────────

    /** 五阶段调用次数 / 累计耗时（纳秒）：Step0~Step4 */
    private final Map<String, AtomicLong> stepCallCount = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> stepTotalNanos = new ConcurrentHashMap<>();

    /** 意图分布：CHAT / KNOWLEDGE / TOOL / MIXED */
    private final Map<String, AtomicLong> intentDistribution = new ConcurrentHashMap<>();
    /** 意图识别失败/降级（解析异常等） */
    private final AtomicLong intentFallbackCount = new AtomicLong();

    /** 监督结果分布：PASS / FAIL / OPTIMIZE */
    private final Map<String, AtomicLong> supervisionDistribution = new ConcurrentHashMap<>();

    /** RAG: 检索调用次数（向量 + BM25 + 评分 整体一次算一次） */
    private final AtomicLong ragRetrievalCount = new AtomicLong();
    /** RAG: 累计召回文档总数（所有迭代加起来） */
    private final AtomicLong ragFusedDocsTotal = new AtomicLong();
    /** RAG: 累计评分通过文档总数 */
    private final AtomicLong ragGoodDocsTotal = new AtomicLong();
    /** RAG: 累计迭代轮数（早停越早越省 token） */
    private final AtomicLong ragIterationsTotal = new AtomicLong();
    /** RAG: query rewriting 触发次数 */
    private final AtomicLong ragQueryRewriteCount = new AtomicLong();
    /** RAG: 达到最大轮次后降级使用融合结果的次数 */
    private final AtomicLong ragFallbackCount = new AtomicLong();

    /** RAG: LLM 文档相关性评分累计（用于计算 avgRelevanceScore） */
    private final AtomicLong ragRelevanceScoreSum = new AtomicLong();
    /** RAG: LLM 文档相关性评分次数 */
    private final AtomicLong ragRelevanceScoreCount = new AtomicLong();

    /** 记忆：长期记忆摘要触发次数（异步压缩并存 pgVector） */
    private final AtomicLong longMemoryConsolidateCount = new AtomicLong();
    /** 记忆：长期记忆检索命中次数（topK > 0） */
    private final AtomicLong longMemoryHitCount = new AtomicLong();
    /** 记忆：长期记忆检索调用总次数 */
    private final AtomicLong longMemoryQueryCount = new AtomicLong();
    /** 记忆：短期记忆摘要压缩触发次数 */
    private final AtomicLong shortMemoryCompressCount = new AtomicLong();

    // ── per-session 计数 ────────────────────────────────────────────────────

    private final Map<String, SessionMetrics> sessionMetricsMap = new ConcurrentHashMap<>();

    // ── 记录方法 ────────────────────────────────────────────────────────────

    public void recordStepDuration(String sessionId, String stepName, long durationNanos) {
        stepCallCount.computeIfAbsent(stepName, k -> new AtomicLong()).incrementAndGet();
        stepTotalNanos.computeIfAbsent(stepName, k -> new AtomicLong()).addAndGet(durationNanos);
        sessionMetrics(sessionId).recordStepDuration(stepName, durationNanos);
    }

    public void recordIntent(String sessionId, String intentType, boolean fallback) {
        if (intentType != null) {
            intentDistribution.computeIfAbsent(intentType, k -> new AtomicLong()).incrementAndGet();
        }
        if (fallback) {
            intentFallbackCount.incrementAndGet();
        }
        sessionMetrics(sessionId).recordIntent(intentType, fallback);
    }

    public void recordSupervision(String sessionId, String passResult) {
        if (passResult != null) {
            supervisionDistribution.computeIfAbsent(passResult, k -> new AtomicLong()).incrementAndGet();
        }
        sessionMetrics(sessionId).recordSupervision(passResult);
    }

    public void recordRagRetrieval(String sessionId, int fusedDocs, int goodDocs,
                                   int iterations, int rewrites, boolean degraded) {
        ragRetrievalCount.incrementAndGet();
        ragFusedDocsTotal.addAndGet(fusedDocs);
        ragGoodDocsTotal.addAndGet(goodDocs);
        ragIterationsTotal.addAndGet(iterations);
        ragQueryRewriteCount.addAndGet(rewrites);
        if (degraded) {
            ragFallbackCount.incrementAndGet();
        }
        sessionMetrics(sessionId).recordRagRetrieval(fusedDocs, goodDocs, iterations, rewrites, degraded);
    }

    public void recordRagRelevanceScore(String sessionId, double score) {
        long scaled = (long) (score * 1000);
        ragRelevanceScoreSum.addAndGet(scaled);
        ragRelevanceScoreCount.incrementAndGet();
        sessionMetrics(sessionId).recordRagRelevanceScore(score);
    }

    public void recordLongMemoryQuery(String sessionId, int hitCount) {
        longMemoryQueryCount.incrementAndGet();
        if (hitCount > 0) {
            longMemoryHitCount.incrementAndGet();
        }
        sessionMetrics(sessionId).recordLongMemoryQuery(hitCount);
    }

    public void recordLongMemoryConsolidate(String sessionId) {
        longMemoryConsolidateCount.incrementAndGet();
        sessionMetrics(sessionId).recordLongMemoryConsolidate();
    }

    public void recordShortMemoryCompress(String sessionId) {
        shortMemoryCompressCount.incrementAndGet();
        sessionMetrics(sessionId).recordShortMemoryCompress();
    }

    // ── 快照导出 ────────────────────────────────────────────────────────────

    /**
     * 导出全局指标快照 —— 用于 GET /api/v1/agent/metrics
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> root = new LinkedHashMap<>();

        Map<String, Object> steps = new LinkedHashMap<>();
        stepCallCount.forEach((step, cnt) -> {
            long calls = cnt.get();
            long totalNanos = stepTotalNanos.getOrDefault(step, new AtomicLong()).get();
            Map<String, Object> stepStat = new LinkedHashMap<>();
            stepStat.put("calls", calls);
            stepStat.put("totalMs", totalNanos / 1_000_000);
            stepStat.put("avgMs", calls == 0 ? 0 : (totalNanos / 1_000_000) / calls);
            steps.put(step, stepStat);
        });
        root.put("steps", steps);

        root.put("intentDistribution", flatten(intentDistribution));
        root.put("intentFallbackCount", intentFallbackCount.get());

        root.put("supervisionDistribution", flatten(supervisionDistribution));

        Map<String, Object> rag = new LinkedHashMap<>();
        long calls = ragRetrievalCount.get();
        long fused = ragFusedDocsTotal.get();
        long good = ragGoodDocsTotal.get();
        long iters = ragIterationsTotal.get();
        rag.put("retrievalCalls", calls);
        rag.put("avgFusedDocs", calls == 0 ? 0 : fused / calls);
        rag.put("avgGoodDocs", calls == 0 ? 0 : good / calls);
        rag.put("avgIterations", calls == 0 ? 0 : (double) iters / calls);
        rag.put("goodRatio", fused == 0 ? 0 : (double) good / fused);
        rag.put("queryRewriteCount", ragQueryRewriteCount.get());
        rag.put("fallbackCount", ragFallbackCount.get());
        long scoreCount = ragRelevanceScoreCount.get();
        rag.put("avgRelevanceScore", scoreCount == 0 ? 0 : (double) ragRelevanceScoreSum.get() / scoreCount / 1000);
        root.put("rag", rag);

        Map<String, Object> memory = new LinkedHashMap<>();
        long lq = longMemoryQueryCount.get();
        memory.put("longMemoryQuery", lq);
        memory.put("longMemoryHit", longMemoryHitCount.get());
        memory.put("longMemoryHitRatio", lq == 0 ? 0 : (double) longMemoryHitCount.get() / lq);
        memory.put("longMemoryConsolidate", longMemoryConsolidateCount.get());
        memory.put("shortMemoryCompress", shortMemoryCompressCount.get());
        root.put("memory", memory);

        root.put("activeSessions", sessionMetricsMap.size());
        return root;
    }

    /**
     * 导出指定 session 的快照
     */
    public Map<String, Object> snapshotSession(String sessionId) {
        SessionMetrics sm = sessionMetricsMap.get(sessionId);
        return sm == null ? new HashMap<>() : sm.snapshot();
    }

    /** 主要用于测试：清零所有指标 */
    public void reset() {
        stepCallCount.clear();
        stepTotalNanos.clear();
        intentDistribution.clear();
        intentFallbackCount.set(0);
        supervisionDistribution.clear();
        ragRetrievalCount.set(0);
        ragFusedDocsTotal.set(0);
        ragGoodDocsTotal.set(0);
        ragIterationsTotal.set(0);
        ragQueryRewriteCount.set(0);
        ragFallbackCount.set(0);
        longMemoryQueryCount.set(0);
        longMemoryHitCount.set(0);
        longMemoryConsolidateCount.set(0);
        shortMemoryCompressCount.set(0);
        sessionMetricsMap.clear();
    }

    private SessionMetrics sessionMetrics(String sessionId) {
        if (sessionId == null) {
            // 不计入 session 维度，给一个临时对象（不入库）
            return new SessionMetrics(null);
        }
        return sessionMetricsMap.computeIfAbsent(sessionId, SessionMetrics::new);
    }

    private static Map<String, Long> flatten(Map<String, AtomicLong> src) {
        Map<String, Long> out = new LinkedHashMap<>();
        src.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }

    // ── per-session 指标内部类 ──────────────────────────────────────────────

    @Getter
    public static class SessionMetrics {
        private final String sessionId;
        private final long createTime = System.currentTimeMillis();
        private final Map<String, AtomicLong> stepCalls = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> stepNanos = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> intent = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> supervision = new ConcurrentHashMap<>();
        private final AtomicLong ragCalls = new AtomicLong();
        private final AtomicLong ragFused = new AtomicLong();
        private final AtomicLong ragGood = new AtomicLong();
        private final AtomicLong ragIters = new AtomicLong();
        private final AtomicLong ragRewrites = new AtomicLong();
        private final AtomicLong ragDegraded = new AtomicLong();
        private final AtomicLong ragScoreSum = new AtomicLong();
        private final AtomicLong ragScoreCount = new AtomicLong();
        private final AtomicLong longMemQuery = new AtomicLong();
        private final AtomicLong longMemHit = new AtomicLong();
        private final AtomicLong longMemConsolidate = new AtomicLong();
        private final AtomicLong shortMemCompress = new AtomicLong();

        public SessionMetrics(String sessionId) {
            this.sessionId = sessionId;
        }

        public void recordStepDuration(String step, long nanos) {
            stepCalls.computeIfAbsent(step, k -> new AtomicLong()).incrementAndGet();
            stepNanos.computeIfAbsent(step, k -> new AtomicLong()).addAndGet(nanos);
        }

        public void recordIntent(String type, boolean fallback) {
            if (type != null) intent.computeIfAbsent(type, k -> new AtomicLong()).incrementAndGet();
        }

        public void recordSupervision(String pass) {
            if (pass != null) supervision.computeIfAbsent(pass, k -> new AtomicLong()).incrementAndGet();
        }

        public void recordRagRelevanceScore(double score) {
            ragScoreSum.addAndGet((long) (score * 1000));
            ragScoreCount.incrementAndGet();
        }

        public void recordRagRetrieval(int fused, int good, int iter, int rewrite, boolean degraded) {
            ragCalls.incrementAndGet();
            ragFused.addAndGet(fused);
            ragGood.addAndGet(good);
            ragIters.addAndGet(iter);
            ragRewrites.addAndGet(rewrite);
            if (degraded) ragDegraded.incrementAndGet();
        }

        public void recordLongMemoryQuery(int hit) {
            longMemQuery.incrementAndGet();
            if (hit > 0) longMemHit.incrementAndGet();
        }

        public void recordLongMemoryConsolidate() { longMemConsolidate.incrementAndGet(); }
        public void recordShortMemoryCompress() { shortMemCompress.incrementAndGet(); }

        public Map<String, Object> snapshot() {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("sessionId", sessionId);
            root.put("createTime", createTime);
            Map<String, Object> steps = new LinkedHashMap<>();
            stepCalls.forEach((s, c) -> {
                long calls = c.get();
                long nanos = stepNanos.getOrDefault(s, new AtomicLong()).get();
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("calls", calls);
                v.put("totalMs", nanos / 1_000_000);
                v.put("avgMs", calls == 0 ? 0 : (nanos / 1_000_000) / calls);
                steps.put(s, v);
            });
            root.put("steps", steps);
            root.put("intent", flatten(intent));
            root.put("supervision", flatten(supervision));
            Map<String, Object> rag = new LinkedHashMap<>();
            rag.put("calls", ragCalls.get());
            rag.put("fusedDocs", ragFused.get());
            rag.put("goodDocs", ragGood.get());
            rag.put("iterations", ragIters.get());
            rag.put("rewrites", ragRewrites.get());
            rag.put("degraded", ragDegraded.get());
            long sc = ragScoreCount.get();
            rag.put("avgRelevanceScore", sc == 0 ? 0 : (double) ragScoreSum.get() / sc / 1000);
            root.put("rag", rag);
            Map<String, Object> mem = new LinkedHashMap<>();
            mem.put("longMemQuery", longMemQuery.get());
            mem.put("longMemHit", longMemHit.get());
            mem.put("longMemConsolidate", longMemConsolidate.get());
            mem.put("shortMemCompress", shortMemCompress.get());
            root.put("memory", mem);
            return root;
        }
    }
}
