package com.ai.voice.engine.dashscope;

import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.ai.voice.config.DashScopeProperties;
import com.ai.voice.engine.ITtsEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.ByteBuffer;

/**
 * 百炼 DashScope TTS 引擎
 * 使用 SpeechSynthesizer（cosyvoice-v3-flash 等）进行语音合成
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.voice.local.enabled", havingValue = "false", matchIfMissing = true)
public class DashScopeTtsEngine implements ITtsEngine {

    private final DashScopeProperties props;

    /**
     * 调用百炼 SpeechSynthesizer 合成语音
     *
     * @param text  待合成文本
     * @param voice 音色（空则使用配置默认）
     * @return 音频字节数组，失败返回 null
     */
    @Override
    public byte[] synthesize(String text, String voice) {
        // 1. 校验 API Key 与文本
        String apiKey = props.getApiKey();
        if (!StringUtils.hasText(apiKey) || !StringUtils.hasText(text)) return null;

        // 2. 构建参数并调用 SDK
        String voiceId = StringUtils.hasText(voice) ? voice : props.getTts().getVoice();
        SpeechSynthesisParam param = SpeechSynthesisParam.builder()
                .apiKey(apiKey)
                .model(props.getTts().getModel())
                .voice(voiceId)
                .build();

        SpeechSynthesizer synthesizer = new SpeechSynthesizer(param, null);
        try {
            // 3. 获取音频字节并返回
            ByteBuffer buffer = synthesizer.call(text);
            return (buffer != null && buffer.hasArray()) ? buffer.array() : null;
        } catch (Exception e) {
            log.error("DashScope TTS 合成失败: {}", e.getMessage());
            return null;
        } finally {
            try { if (synthesizer.getDuplexApi() != null) synthesizer.getDuplexApi().close(1000, "bye"); }
            catch (Exception ignored) { }
        }
    }
}
