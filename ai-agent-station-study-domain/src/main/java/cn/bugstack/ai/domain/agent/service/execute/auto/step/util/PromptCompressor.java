package cn.bugstack.ai.domain.agent.service.execute.auto.step.util;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt 压缩工具：对 stepPrompt 模板进行运行时去重压缩，减少 token 消耗
 * <p>
 * 主要策略：
 * 1. 去除模板内重复出现的 ```json...``` 代码块（只保留第一次）
 * 2. 当去重后仍超过 maxChars 时，截断并附加省略说明
 * </p>
 *
 * @author claude
 */
@Slf4j
public class PromptCompressor {

    /** 默认最大允许字符数（约 625 tokens），超出后截断 */
    public static final int DEFAULT_MAX_CHARS = 2500;

    private static final Pattern CODE_BLOCK_PATTERN =
            Pattern.compile("```(?:json|\\w*)?\\s*([\\s\\S]*?)```", Pattern.MULTILINE);

    private PromptCompressor() {}

    /**
     * 使用默认字符上限压缩 prompt
     */
    public static String compress(String prompt) {
        return compress(prompt, DEFAULT_MAX_CHARS);
    }

    /**
     * 压缩 prompt：去除重复代码块，超限则截断
     *
     * @param prompt   原始 prompt 模板
     * @param maxChars 最大字符数上限
     * @return 压缩后的 prompt
     */
    public static String compress(String prompt, int maxChars) {
        if (prompt == null || prompt.length() <= maxChars) {
            return prompt;
        }

        int originalLength = prompt.length();
        String result = deduplicateCodeBlocks(prompt);

        if (result.length() < originalLength) {
            log.debug("[PromptCompressor] 去重压缩: {} -> {} chars", originalLength, result.length());
        }

        if (result.length() > maxChars) {
            result = result.substring(0, maxChars) + "\n[...省略重复说明]";
            log.debug("[PromptCompressor] 截断压缩: {} -> {} chars", originalLength, result.length());
        }

        return result;
    }

    /**
     * 去除同一 prompt 内重复出现的代码块（保留第一次出现）
     */
    private static String deduplicateCodeBlocks(String text) {
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder result = new StringBuilder(text.length());
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(text);

        int lastEnd = 0;
        while (matcher.find()) {
            String blockContent = normalizeBlock(matcher.group(1));
            if (seen.contains(blockContent)) {
                // 跳过重复块：追加块之前的文本，跳过本块
                result.append(text, lastEnd, matcher.start());
            } else {
                seen.add(blockContent);
                result.append(text, lastEnd, matcher.end());
            }
            lastEnd = matcher.end();
        }
        result.append(text, lastEnd, text.length());
        return result.toString();
    }

    /**
     * 规范化代码块内容，用于去重比较（忽略注释行差异，只比较结构）
     */
    private static String normalizeBlock(String content) {
        return content.trim()
                .replaceAll("//.*", "")          // 去掉行注释
                .replaceAll("#.*", "")            // 去掉 # 注释
                .replaceAll("\\s+", " ")          // 压缩空白
                .trim();
    }

}
