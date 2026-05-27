package cn.bugstack.ai.domain.agent.service.armory.factory.element;

import cn.bugstack.ai.domain.agent.service.metrics.AgentMetricsRegistry;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Agentic RAG 检索增强顾问
 * <p>
 * 检索内核（每轮均执行）：
 *   1. 向量相似度检索（扩大召回池 POOL_FACTOR 倍）
 *   2. BM25 全文关键词检索（基于 PgVector content_tsvector 列）
 *   3. RRF（Reciprocal Rank Fusion）融合两路结果
 * <p>
 * Agentic 外壳（新增）：
 *   4. 文档质量评分 — LLM 对每篇文档打相关性分数，过滤低质量文档
 *   5. 迭代重试 — 有效文档不足时，LLM 重写 query，最多 MAX_ITERATIONS 轮
 */
public class RagAnswerAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(RagAnswerAdvisor.class);

    // ── 检索内核参数 ──────────────────────────────────────────────────────
    /** RRF 平滑常数 */
    private static final int RRF_K = 60;
    /** 召回池倍数：向量和 BM25 各拉 topK * POOL_FACTOR 条再 RRF */
    private static final int POOL_FACTOR = 3;
    private static final String VECTOR_TABLE = "vector_store_openai";
    private static final Pattern AGENT_ID_PATTERN =
            Pattern.compile("agent_id\\s*==\\s*'([^']+)'", Pattern.CASE_INSENSITIVE);

    // ── Agentic 外壳参数 ──────────────────────────────────────────────────
    /** 最大检索迭代轮次 */
    private static final int MAX_ITERATIONS = 3;
    /** 最少有效文档数，达到即停止迭代 */
    private static final int MIN_GOOD_DOCS = 2;
    /** 文档相关性分数阈值（0.0 ~ 1.0） */
    private static final double GRADE_THRESHOLD = 0.6;
    /** 参与评分的最大文档数（避免 prompt 过长） */
    private static final int MAX_GRADE_DOCS = 10;

    // ── 数字提取 Pattern（解析 LLM 返回的 JSON 数组） ─────────────────────
    private static final Pattern SCORES_ARRAY_PATTERN =
            Pattern.compile("\\[([\\d.,\\s]+)]");

    private final VectorStore vectorStore;
    private final SearchRequest searchRequest;
    private final JdbcTemplate pgVectorJdbcTemplate;
    /** 用于 query rewriting 和 document grading；为 null 时退化为普通 RAG */
    private final ChatModel chatModel;
    /** 可观测指标注册中心，可为 null（如手动构造时） */
    private final AgentMetricsRegistry metricsRegistry;
    private final String userTextAdvise;

    public RagAnswerAdvisor(VectorStore vectorStore, SearchRequest searchRequest,
                            JdbcTemplate pgVectorJdbcTemplate, ChatModel chatModel) {
        this(vectorStore, searchRequest, pgVectorJdbcTemplate, chatModel, null);
    }

    public RagAnswerAdvisor(VectorStore vectorStore, SearchRequest searchRequest,
                            JdbcTemplate pgVectorJdbcTemplate, ChatModel chatModel,
                            AgentMetricsRegistry metricsRegistry) {
        this.vectorStore = vectorStore;
        this.searchRequest = searchRequest;
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
        this.chatModel = chatModel;
        this.metricsRegistry = metricsRegistry;
        this.userTextAdvise = "\nContext information is below, surrounded by ---------------------\n\n"
                + "---------------------\n{question_answer_context}\n---------------------\n\n"
                + "Given the context and provided history information and not prior knowledge,\n"
                + "reply to the user comment. If the answer is not in the context, inform\n"
                + "the user that you can't answer the question.\n";
    }

    // ── Advisor 主入口 ────────────────────────────────────────────────────

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        HashMap<String, Object> context = new HashMap<>(chatClientRequest.context());

        String userText = chatClientRequest.prompt().getUserMessage().getText();
        String advisedUserText = userText + System.lineSeparator() + this.userTextAdvise;
        String originalQuery = new PromptTemplate(userText).render();

        Filter.Expression filterExpr = doGetFilterExpression(context);
        int topK = this.searchRequest.getTopK();
        int poolSize = topK * POOL_FACTOR;
        String agentId = extractAgentId(filterExpr);

        // ── Agentic RAG 迭代循环 ──────────────────────────────────────────
        String currentQuery = originalQuery;
        List<Document> finalDocuments = Collections.emptyList();
        String failureReason = null;

        // metrics 累积量（循环结束统一上报）
        int totalIterations = 0;
        int totalRewrites = 0;
        int lastFusedSize = 0;
        int lastGoodSize = 0;
        boolean degraded = false;

        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            totalIterations = iter + 1;
            // 第 1 轮用原始 query；后续轮由 LLM 重写
            if (iter > 0) {
                currentQuery = rewriteQuery(originalQuery, currentQuery, iter, failureReason);
                totalRewrites++;
                log.info("Agentic RAG 第{}轮，重写 query: {}", iter + 1, currentQuery);
            }

            // ── 内核检索（每轮均执行，逻辑与原 before() 完全一致） ──────────
            SearchRequest vectorReq = SearchRequest.from(this.searchRequest)
                    .query(currentQuery).topK(poolSize).filterExpression(filterExpr).build();
            List<Document> vectorResults = this.vectorStore.similaritySearch(vectorReq);

            List<Document> bm25Results = bm25Search(currentQuery, poolSize, agentId);

            // RRF 先多拉一些，评分后再截断到 topK
            List<Document> fusedDocs = rrfFusion(vectorResults, bm25Results, topK * 2);
            lastFusedSize = fusedDocs.size();

            // ── 文档质量评分（Agentic 新增） ──────────────────────────────
            List<Document> gradedDocs = gradeDocuments(originalQuery, fusedDocs, topK);
            lastGoodSize = gradedDocs.size();

            if (gradedDocs.size() >= MIN_GOOD_DOCS) {
                finalDocuments = gradedDocs.subList(0, Math.min(topK, gradedDocs.size()));
                log.info("Agentic RAG 第{}轮检索成功，有效文档: {}篇", iter + 1, finalDocuments.size());
                break;
            }

            failureReason = String.format("第%d轮只找到%d篇相关文档（需要至少%d篇）",
                    iter + 1, gradedDocs.size(), MIN_GOOD_DOCS);
            log.warn("Agentic RAG {}", failureReason);

            // 最后一轮仍不足 → 降级：用 RRF 融合结果（不过滤）
            if (iter == MAX_ITERATIONS - 1) {
                finalDocuments = fusedDocs.subList(0, Math.min(topK, fusedDocs.size()));
                degraded = true;
                log.warn("Agentic RAG 达到最大轮次，降级使用融合结果: {}篇", finalDocuments.size());
            }
        }

        if (metricsRegistry != null) {
            // sessionId 在 advisor 链里通过 chat_memory_conversation_id 传入；这里 best-effort 取值
            Object sid = context.get("chat_memory_conversation_id");
            metricsRegistry.recordRagRetrieval(sid != null ? sid.toString() : null,
                    lastFusedSize, lastGoodSize, totalIterations, totalRewrites, degraded);
        }

        // ── 注入 context ──────────────────────────────────────────────────
        context.put("qa_retrieved_documents", finalDocuments);
        String documentContext = finalDocuments.stream()
                .map(Document::getText)
                .collect(Collectors.joining(System.lineSeparator()));

        Map<String, Object> advisedUserParams = new HashMap<>(context);
        advisedUserParams.put("question_answer_context", documentContext);

        return ChatClientRequest.builder()
                .prompt(Prompt.builder()
                        .messages(new UserMessage(advisedUserText),
                                new AssistantMessage(JSON.toJSONString(advisedUserParams)))
                        .build())
                .context(advisedUserParams)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        ChatResponse.Builder builder = ChatResponse.builder().from(chatClientResponse.chatResponse());
        builder.metadata("qa_retrieved_documents", chatClientResponse.context().get("qa_retrieved_documents"));
        return ChatClientResponse.builder()
                .chatResponse(builder.build())
                .context(chatClientResponse.context())
                .build();
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        ChatClientResponse response = callAdvisorChain.nextCall(this.before(chatClientRequest, callAdvisorChain));
        return this.after(response, callAdvisorChain);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
                                                  StreamAdvisorChain streamAdvisorChain) {
        return BaseAdvisor.super.adviseStream(chatClientRequest, streamAdvisorChain);
    }

    @Override
    public int getOrder() { return 0; }

    @Override
    public String getName() { return this.getClass().getSimpleName(); }

    // ── Agentic 方法 ──────────────────────────────────────────────────────

    /**
     * Query Rewriting：让 LLM 根据失败原因重写检索 query。
     * 失败时原样返回 previousQuery（不阻断流程）。
     */
    private String rewriteQuery(String originalQuery, String previousQuery,
                                 int iter, String failureReason) {
        if (chatModel == null) return previousQuery;
        try {
            String prompt = String.format(
                    "你是一个检索查询改写助手。用户的问题在第%d次向量+关键词检索后未能找到足够相关的文档。\n"
                    + "原始问题: %s\n"
                    + "上一轮查询: %s\n"
                    + "失败原因: %s\n\n"
                    + "请将查询改写得更具体、语义更丰富，以便检索到更多相关内容。\n"
                    + "只返回改写后的查询语句，不要任何解释。",
                    iter, originalQuery, previousQuery,
                    failureReason != null ? failureReason : "相关文档不足");

            String result = chatModel.call(new Prompt(prompt))
                    .getResult().getOutput().getText();
            return StringUtils.hasText(result) ? result.trim() : previousQuery;
        } catch (Exception e) {
            log.warn("Agentic RAG 查询改写失败，沿用原查询: {}", e.getMessage());
            return previousQuery;
        }
    }

    /**
     * Document Grading：LLM 对每篇文档打 0.0~1.0 的相关性分数，过滤低于阈值的文档。
     * 评分失败时降级返回前 topK 篇文档（不阻断流程）。
     */
    private List<Document> gradeDocuments(String query, List<Document> docs, int topK) {
        if (docs.isEmpty()) return docs;
        if (chatModel == null) {
            return docs.subList(0, Math.min(topK, docs.size()));
        }

        // 截断参与评分的文档数，避免 prompt 过长
        List<Document> candidates = docs.size() > MAX_GRADE_DOCS
                ? docs.subList(0, MAX_GRADE_DOCS) : docs;

        try {
            StringBuilder sb = new StringBuilder();
            sb.append("请对以下每篇文档与查询的相关性评分（0.0=完全不相关，1.0=高度相关）。\n");
            sb.append("查询: ").append(query).append("\n\n");
            for (int i = 0; i < candidates.size(); i++) {
                String text = candidates.get(i).getText();
                String snippet = text.length() > 300 ? text.substring(0, 300) + "..." : text;
                sb.append("[").append(i).append("]: ").append(snippet).append("\n");
            }
            sb.append("\n只返回一个 JSON 数组，例如: [0.8, 0.3, 0.9]，顺序与文档一致，不要任何解释。");

            String response = chatModel.call(new Prompt(sb.toString()))
                    .getResult().getOutput().getText().trim();

            List<Double> scores = parseScores(response, candidates.size());

            // 记录 LLM 评分用于在线准确率监控
            if (metricsRegistry != null) {
                for (int i = 0; i < scores.size() && i < candidates.size(); i++) {
                    metricsRegistry.recordRagRelevanceScore(null, scores.get(i));
                }
            }

            // 按分数过滤，保留 >= GRADE_THRESHOLD 的文档
            List<Document> graded = new ArrayList<>();
            for (int i = 0; i < scores.size() && i < candidates.size(); i++) {
                if (scores.get(i) >= GRADE_THRESHOLD) {
                    graded.add(candidates.get(i));
                }
            }
            log.info("Agentic RAG 文档评分完成: 候选{}篇 → 有效{}篇 (阈值{})",
                    candidates.size(), graded.size(), GRADE_THRESHOLD);
            return graded;
        } catch (Exception e) {
            log.warn("Agentic RAG 文档评分失败，降级返回融合结果: {}", e.getMessage());
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }
    }

    /**
     * 解析 LLM 返回的评分 JSON 数组，如 [0.8, 0.3, 0.9]。
     * 解析失败时返回全 1.0（保留所有文档）。
     */
    private List<Double> parseScores(String response, int expectedSize) {
        Matcher matcher = SCORES_ARRAY_PATTERN.matcher(response);
        if (matcher.find()) {
            String[] parts = matcher.group(1).split(",");
            List<Double> scores = new ArrayList<>();
            for (String part : parts) {
                try {
                    scores.add(Double.parseDouble(part.trim()));
                } catch (NumberFormatException e) {
                    scores.add(0.0);
                }
            }
            return scores;
        }
        // 解析失败 → 全保留
        List<Double> defaults = new ArrayList<>(expectedSize);
        for (int i = 0; i < expectedSize; i++) defaults.add(1.0);
        return defaults;
    }

    // ── 原有内核方法（一行不改） ──────────────────────────────────────────

    protected Filter.Expression doGetFilterExpression(Map<String, Object> context) {
        if (context.containsKey("qa_filter_expression")
                && StringUtils.hasText(context.get("qa_filter_expression").toString())) {
            return new FilterExpressionTextParser().parse(context.get("qa_filter_expression").toString());
        }
        return this.searchRequest.getFilterExpression();
    }

    private String extractAgentId(Filter.Expression filterExpression) {
        if (filterExpression == null) return null;
        Matcher m = AGENT_ID_PATTERN.matcher(filterExpression.toString());
        return m.find() ? m.group(1) : null;
    }

    private List<Document> bm25Search(String query, int topK, String agentId) {
        if (pgVectorJdbcTemplate == null || !StringUtils.hasText(query)) {
            return List.of();
        }
        try {
            String sql;
            Object[] params;
            if (StringUtils.hasText(agentId)) {
                sql = "SELECT content, metadata::text FROM " + VECTOR_TABLE
                        + " WHERE content_tsvector @@ plainto_tsquery('simple', ?)"
                        + "   AND metadata->>'agent_id' = ?"
                        + " ORDER BY ts_rank(content_tsvector, plainto_tsquery('simple', ?)) DESC"
                        + " LIMIT ?";
                params = new Object[]{query, agentId, query, topK};
            } else {
                sql = "SELECT content, metadata::text FROM " + VECTOR_TABLE
                        + " WHERE content_tsvector @@ plainto_tsquery('simple', ?)"
                        + " ORDER BY ts_rank(content_tsvector, plainto_tsquery('simple', ?)) DESC"
                        + " LIMIT ?";
                params = new Object[]{query, query, topK};
            }
            return pgVectorJdbcTemplate.query(sql, (rs, rowNum) -> {
                String content = rs.getString("content");
                Map<String, Object> meta = JSON.parseObject(
                        rs.getString("metadata"), new TypeReference<Map<String, Object>>() {});
                return new Document(content, meta);
            }, params);
        } catch (Exception e) {
            log.warn("BM25 检索失败，降级为纯向量检索: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Document> rrfFusion(List<Document> vectorResults,
                                     List<Document> bm25Results, int topK) {
        Map<Integer, Double> scores = new LinkedHashMap<>();
        Map<Integer, Document> docMap = new LinkedHashMap<>();
        addToRrf(vectorResults, scores, docMap);
        addToRrf(bm25Results, scores, docMap);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> docMap.get(e.getKey()))
                .collect(Collectors.toList());
    }

    private void addToRrf(List<Document> docs, Map<Integer, Double> scores,
                          Map<Integer, Document> docMap) {
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            int key = doc.getText().hashCode();
            scores.merge(key, 1.0 / (RRF_K + i + 1), Double::sum);
            docMap.putIfAbsent(key, doc);
        }
    }
}
