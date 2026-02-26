package com.ai.voice.service.impl;

import com.ai.voice.newapi.NewApiChatUtils;
import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.ai.voice.config.DashScopeProperties;
import com.ai.voice.config.TextCorrectionProperties;
import com.ai.voice.service.ITextCorrectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * ASR 文本纠错服务：使用百炼 LLM 对识别结果进行纠错
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TextCorrectionServiceImpl implements ITextCorrectionService {

    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是一个语音识别文本纠错助手。用户发来的文本是语音识别（ASR）的原始输出，可能存在错别字、同音字替换、语序不通顺等问题。
            请你完成以下任务：
            1. 修正明显的识别错误（错别字、同音字、漏字、多字）
            2. 保持用户的原始意图，不要添加、删除或改变用户表达的核心信息
            3. 只输出纠错后的文本，不要输出任何解释""";

    private final DashScopeProperties props;
    private final TextCorrectionProperties textCorrectionProps;
    private final NewApiChatUtils newApiChatUtils;
    /**
     * 使用配置的 LLM 对 ASR 原始文本纠错，API Key 未配置或调用失败时返回原文
     *
     * @param rawText ASR 原始输出
     * @return 纠错后文本
     */
    @Override
    public String correct(String rawText) {
        // 1. 校验入参
        if (!StringUtils.hasText(rawText)) return rawText;

        // 2. 构建 LLM 请求并调用
        String systemPrompt = StringUtils.hasText(textCorrectionProps.getSystemPrompt())
                ? textCorrectionProps.getSystemPrompt() : DEFAULT_SYSTEM_PROMPT;
        try {
             String corrected = newApiChatUtils.chat(props.getLlm().getModel(), systemPrompt, rawText);
            return StringUtils.hasText(corrected) ? corrected.trim() : rawText;
        } catch (Exception e) {
            log.warn("LLM 纠错失败，返回原文: {}", e.getMessage());
            return rawText;
        }
    }
}
