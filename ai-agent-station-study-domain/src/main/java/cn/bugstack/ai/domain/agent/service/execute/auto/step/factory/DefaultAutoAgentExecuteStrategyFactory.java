package cn.bugstack.ai.domain.agent.service.execute.auto.step.factory;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.RootNode;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工厂类
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/7/27 16:34
 */
@Service
public class DefaultAutoAgentExecuteStrategyFactory {

    private final RootNode executeRootNode;

    public DefaultAutoAgentExecuteStrategyFactory(RootNode executeRootNode) {
        this.executeRootNode = executeRootNode;
    }

    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> armoryStrategyHandler(){
        return executeRootNode;
    }

    /**
     * 意图识别结果
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class IntentResult {
        /** CHAT / KNOWLEDGE / TOOL / MIXED */
        private String intentType;
        /** 是否需要查询 RAG 知识库 */
        private boolean needsRag;
        /** 是否需要调用 MCP 工具 */
        private boolean needsMcp;
        /** 判断理由（供日志/SSE展示） */
        private String reasoning;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {

        // 任务执行步骤
        private int step = 1;

        // 最大任务步骤
        private int maxStep = 1;

        // 执行历史条目列表（按步骤存储，支持滑动窗口）
        private List<String> historyEntries = new ArrayList<>();

        // 滑动窗口大小：Step1 分析时只传入最近 N 步历史，降低 token 消耗
        private int historyWindowSize = 2;

        private String currentTask;

        boolean isCompleted = false;

        private Map<String, AiAgentClientFlowConfigVO> aiAgentClientFlowConfigVOMap;

        private Map<String, Object> dataObjects = new HashMap<>();

        /** 意图识别结果，由 Step0 写入，Step2 读取 */
        private IntentResult intentResult;

        /**
         * 获取最近 historyWindowSize 步的历史（用于 Step1 分析阶段，控制 token 窗口）
         */
        public String getWindowedHistory() {
            if (historyEntries == null || historyEntries.isEmpty()) return "";
            int size = historyEntries.size();
            int from = Math.max(0, size - historyWindowSize);
            return String.join("\n", historyEntries.subList(from, size));
        }

        /**
         * 获取全量历史（用于 Step4 最终汇总，保证完整性）
         */
        public String getFullHistory() {
            if (historyEntries == null || historyEntries.isEmpty()) return "";
            return String.join("\n", historyEntries);
        }

        public <T> void setValue(String key, T value) {
            dataObjects.put(key, value);
        }

        public <T> T getValue(String key) {
            return (T) dataObjects.get(key);
        }
    }

}
