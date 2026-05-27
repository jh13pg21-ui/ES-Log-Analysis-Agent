package cn.bugstack.ai.test.rag;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RAG 召回准确率评估
 * <p>
 * 指标说明：
 * - Hit Rate    : 至少召回 1 篇相关文档的查询占比（越高越好，理想 > 0.8）
 * - Precision@K : 召回 K 篇中相关文档占比（越高越好，理想 > 0.6）
 * - MRR         : 第一篇相关文档的排名的倒数均值（越高越好，理想 > 0.5）
 * - Recall@K    : 所有相关文档中被召回的比例（需要知道全量相关文档，这里用关键词匹配近似）
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("dev")
public class RagEvalTest {

    @Resource
    private VectorStore vectorStore;

    @Resource(name = "pgVectorJdbcTemplate")
    private JdbcTemplate pgVectorJdbcTemplate;

    private static final String VECTOR_TABLE = "vector_store_openai";
    private static final int TOP_K = 5;
    private static final int POOL_FACTOR = 3;
    private static final int RRF_K = 60;

    @Test
    @DisplayName("RAG 离线评估：Precision@K / Recall@K / MRR / Hit Rate")
    public void evaluateRagAccuracy() throws Exception {
        List<EvalCase> cases = loadDataset();
        log.info("加载评估数据集: {} 条测试用例", cases.size());

        List<EvalResult> results = new ArrayList<>();
        for (EvalCase c : cases) {
            EvalResult r = evaluateOne(c, TOP_K);
            results.add(r);
            log.info("Q[{}]: precision@{}={:.2f} recall={:.2f} mrr={:.2f} hit={}",
                    c.getQuery().substring(0, Math.min(30, c.getQuery().length())),
                    TOP_K, r.precision, r.recall, r.mrr, r.hit);
        }

        // 汇总指标
        double avgPrecision = results.stream().mapToDouble(r -> r.precision).average().orElse(0);
        double avgRecall = results.stream().mapToDouble(r -> r.recall).average().orElse(0);
        double avgMrr = results.stream().mapToDouble(r -> r.mrr).average().orElse(0);
        double hitRate = (double) results.stream().filter(r -> r.hit).count() / results.size();

        log.info("\n========== RAG 评估结果汇总 ==========");
        log.info("测试用例数   : {}", cases.size());
        log.info("Top-K        : {}", TOP_K);
        log.info("Hit Rate     : {:.2f} (>=0.80 合格)", hitRate);
        log.info("Precision@{} : {:.2f} (>=0.60 合格)", TOP_K, avgPrecision);
        log.info("Recall@{}    : {:.2f} (>=0.50 合格)", TOP_K, avgRecall);
        log.info("MRR          : {:.2f} (>=0.50 合格)", avgMrr);

        // 失败用例详情
        List<EvalResult> failures = results.stream().filter(r -> !r.hit).toList();
        if (!failures.isEmpty()) {
            log.warn("\n--- Hit Rate = 0 的用例 ({} 条) ---", failures.size());
            for (EvalResult f : failures) {
                log.warn("  Query: {}", f.query);
                log.warn("  Expected keywords: {}", f.expectedKeywords);
                log.warn("  Retrieved {} docs, top3 content preview:", f.retrievedCount);
                for (int i = 0; i < Math.min(3, f.topDocContents.size()); i++) {
                    String snippet = f.topDocContents.get(i).length() > 100
                            ? f.topDocContents.get(i).substring(0, 100) + "..."
                            : f.topDocContents.get(i);
                    log.warn("    [{}] {}", i + 1, snippet);
                }
            }
        }
    }

    private EvalResult evaluateOne(EvalCase c, int topK) {
        // 1. 向量检索
        SearchRequest vectorReq = SearchRequest.builder()
                .query(c.query).topK(topK * POOL_FACTOR).build();
        List<Document> vectorResults = vectorStore.similaritySearch(vectorReq);

        // 2. BM25 检索
        List<Document> bm25Results = bm25Search(c.query, topK * POOL_FACTOR);

        // 3. RRF 融合
        List<Document> fused = rrfFusion(vectorResults, bm25Results, topK);

        // 4. 关键词匹配判断相关性
        int relevantCount = 0;
        int firstRelevantRank = -1;
        List<String> topContents = new ArrayList<>();

        for (int i = 0; i < fused.size(); i++) {
            String content = fused.get(i).getText();
            topContents.add(content);
            if (containsAnyKeyword(content, c.expectedKeywords)) {
                relevantCount++;
                if (firstRelevantRank == -1) {
                    firstRelevantRank = i + 1;
                }
            }
        }

        boolean hit = relevantCount > 0;
        double precision = fused.isEmpty() ? 0 : (double) relevantCount / fused.size();
        double recall = c.minRelevantDocs == 0 ? 0 : (double) relevantCount / c.minRelevantDocs;
        double mrr = firstRelevantRank > 0 ? 1.0 / firstRelevantRank : 0;

        EvalResult r = new EvalResult();
        r.query = c.query;
        r.expectedKeywords = c.expectedKeywords;
        r.hit = hit;
        r.precision = precision;
        r.recall = Math.min(recall, 1.0);
        r.mrr = mrr;
        r.retrievedCount = fused.size();
        r.relevantCount = relevantCount;
        r.topDocContents = topContents;
        return r;
    }

    private boolean containsAnyKeyword(String text, List<String> keywords) {
        String lower = text.toLowerCase();
        return keywords.stream().anyMatch(k -> lower.contains(k.toLowerCase()));
    }

    private List<Document> bm25Search(String query, int topK) {
        if (!StringUtils.hasText(query)) return List.of();
        try {
            String sql = "SELECT content, metadata::text FROM " + VECTOR_TABLE
                    + " WHERE content_tsvector @@ plainto_tsquery('simple', ?)"
                    + " ORDER BY ts_rank(content_tsvector, plainto_tsquery('simple', ?)) DESC LIMIT ?";
            return pgVectorJdbcTemplate.query(sql,
                    (rs, rowNum) -> {
                        Map<String, Object> meta = JSON.parseObject(
                                rs.getString("metadata"), new TypeReference<Map<String, Object>>() {});
                        return new Document(rs.getString("content"), meta);
                    },
                    query, query, topK);
        } catch (Exception e) {
            log.warn("BM25 检索失败 (PgVector 可能未安装 zhparser): {}", e.getMessage());
            return List.of();
        }
    }

    private List<Document> rrfFusion(List<Document> vec, List<Document> bm25, int topK) {
        Map<Integer, Double> scores = new LinkedHashMap<>();
        Map<Integer, Document> docMap = new LinkedHashMap<>();
        addToRrf(vec, scores, docMap);
        addToRrf(bm25, scores, docMap);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> docMap.get(e.getKey()))
                .collect(Collectors.toList());
    }

    private void addToRrf(List<Document> docs, Map<Integer, Double> scores, Map<Integer, Document> docMap) {
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            int key = doc.getText().hashCode();
            scores.merge(key, 1.0 / (RRF_K + i + 1), Double::sum);
            docMap.putIfAbsent(key, doc);
        }
    }

    private List<EvalCase> loadDataset() throws Exception {
        ClassPathResource resource = new ClassPathResource("rag-eval-dataset.json");
        try (InputStream is = resource.getInputStream()) {
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return JSON.parseObject(json, new TypeReference<List<EvalCase>>() {});
        }
    }

    // ── 数据类 ──

    @lombok.Data
    public static class EvalCase {
        private String query;
        private List<String> expectedDocIds;
        private List<String> expectedKeywords;
        private int minRelevantDocs;
    }

    @lombok.Data
    public static class EvalResult {
        private String query;
        private List<String> expectedKeywords;
        private boolean hit;
        private double precision;
        private double recall;
        private double mrr;
        private int retrievedCount;
        private int relevantCount;
        private List<String> topDocContents;
    }
}
