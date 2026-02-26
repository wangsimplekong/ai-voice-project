package com.ai.voice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ASR 文本纠错配置（LLM 纠错用系统提示词等）
 * 可从本地 application.yml 或 Nacos 配置中心加载，便于后续修改
 */
@Data
@ConfigurationProperties(prefix = "ai.voice.text-correction")
public class TextCorrectionProperties {

    /**
     * 纠错 LLM 的系统提示词，未配置时使用代码内默认值
     */
    private String systemPrompt;
}
