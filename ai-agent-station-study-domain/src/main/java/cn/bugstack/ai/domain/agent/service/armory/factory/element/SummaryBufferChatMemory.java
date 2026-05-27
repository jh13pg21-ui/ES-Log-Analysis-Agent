package cn.bugstack.ai.domain.agent.service.armory.factory.element;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 摘要缓冲短期记忆
 * <p>
 * 解决 MessageWindowChatMemory 超出 maxMessages 时硬截断导致的遗忘问题。
 * <p>
 * 工作机制：
 *   明细区：保存最近 maxRawMessages 条完整消息
 *   摘要区：LLM 对最老的 compressSize 条消息生成摘要，以 SystemMessage 形式
 *           追加在明细区最前面传给 LLM，保留压缩后的语义信息
 * <p>
 * 触发时机：每次 add() 后若明细区超出 maxRawMessages，立即同步压缩（在响应返回前完成，
 *   确保下一轮 get() 时摘要已就绪）
 */
public class SummaryBufferChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(SummaryBufferChatMemory.class);

    /** 明细区最大消息条数；超出后将最老的 compressSize 条压缩进摘要 */
    private final int maxRawMessages;
    /** 每次触发压缩时处理的消息条数 */
    private final int compressSize;
    private final ChatModel chatModel;

    /** conversationId → 当前累积摘要文本（可能经过多轮追加压缩） */
    private final ConcurrentHashMap<String, String> summaryStore = new ConcurrentHashMap<>();
    /** conversationId → 明细区消息列表（线程安全由外部 synchronized 保证） */
    private final ConcurrentHashMap<String, List<Message>> rawStore = new ConcurrentHashMap<>();

    public SummaryBufferChatMemory(ChatModel chatModel, int maxRawMessages, int compressSize) {
        this.chatModel = chatModel;
        this.maxRawMessages = maxRawMessages;
        // compressSize 不能超过 maxRawMessages，否则压缩后 raw 仍为空
        this.compressSize = Math.min(compressSize, maxRawMessages);
    }

    // ── ChatMemory 接口实现 ──────────────────────────────────────────────────

    @Override
    public void add(String conversationId, List<Message> messages) {
        List<Message> raw = rawStore.computeIfAbsent(conversationId, k -> new ArrayList<>());
        synchronized (raw) {
            raw.addAll(messages);
            // 超出明细区上限时，压缩最老的 compressSize 条
            if (raw.size() > maxRawMessages) {
                int count = Math.min(compressSize, raw.size() - maxRawMessages + compressSize);
                List<Message> toCompress = new ArrayList<>(raw.subList(0, count));
                String newSummary = compress(summaryStore.get(conversationId), toCompress, conversationId);
                if (StringUtils.hasText(newSummary)) {
                    summaryStore.put(conversationId, newSummary);
                }
                // 无论压缩是否成功都移除这批消息，避免 raw 无限增长
                raw.subList(0, count).clear();
            }
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        List<Message> result = new ArrayList<>();

        // 摘要区：以 SystemMessage 形式置于消息列表最前
        String summary = summaryStore.get(conversationId);
        if (StringUtils.hasText(summary)) {
            result.add(new SystemMessage("[之前对话摘要 - 仅供参考]\n" + summary));
        }

        // 明细区：返回全部（由本类自行管理窗口大小，不再二次截断）
        List<Message> raw = rawStore.get(conversationId);
        if (raw != null) {
            synchronized (raw) {
                result.addAll(raw);
            }
        }
        return result;
    }

    @Override
    public void clear(String conversationId) {
        summaryStore.remove(conversationId);
        rawStore.remove(conversationId);
    }

    // ── 核心：摘要压缩 ────────────────────────────────────────────────────────

    /**
     * 将旧摘要与待压缩消息合并，由 LLM 生成新摘要。
     * 压缩失败时返回旧摘要（降级保护）。
     */
    private String compress(String existingSummary, List<Message> messages, String conversationId) {
        try {
            StringBuilder dialogue = new StringBuilder();
            if (StringUtils.hasText(existingSummary)) {
                dialogue.append("之前对话的摘要：\n").append(existingSummary).append("\n\n");
            }
            dialogue.append("新增对话内容：\n");
            for (Message msg : messages) {
                String role = msg instanceof AssistantMessage ? "助手" : "用户";
                dialogue.append(role).append(": ").append(msg.getText()).append("\n");
            }

            String promptText = "请将以下内容（可能包含之前的摘要和新增对话）合并压缩为一段连贯的摘要，"
                    + "保留关键事实、用户意图和重要结论，不超过300字。\n\n"
                    + dialogue
                    + "\n只返回摘要正文，不要任何前缀或解释。";

            String summary = chatModel.call(new Prompt(promptText))
                    .getResult().getOutput().getText();

            if (!StringUtils.hasText(summary)) {
                log.warn("SummaryBufferChatMemory: LLM 返回空摘要，保留旧摘要 conversationId={}", conversationId);
                return existingSummary;
            }

            log.info("SummaryBufferChatMemory: 压缩完成 conversationId={} 压缩{}条 摘要={}字",
                    conversationId, messages.size(), summary.trim().length());
            return summary.trim();

        } catch (Exception e) {
            log.error("SummaryBufferChatMemory: 压缩失败 conversationId={}: {}", conversationId, e.getMessage(), e);
            return existingSummary;
        }
    }
}
