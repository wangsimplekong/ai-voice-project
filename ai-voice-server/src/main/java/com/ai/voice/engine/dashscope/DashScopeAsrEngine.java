package com.ai.voice.engine.dashscope;

import com.ai.voice.config.DashScopeProperties;
import com.ai.voice.engine.AsrSessionConfig;
import com.ai.voice.engine.IAsrEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 百炼实时 ASR 引擎路由器，根据 asr.model 自动选择 SDK API：
 * <ul>
 *   <li>qwen3-asr-* / qwen-omni-* → OmniRealtime</li>
 *   <li>gummy-chat-* → GummyChat（一句话识别）</li>
 *   <li>gummy-* → GummyRealtime</li>
 *   <li>其他（fun-asr-* / paraformer-* / sensevoice-*）→ Recognition</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.voice.local.enabled", havingValue = "false", matchIfMissing = true)
public class DashScopeAsrEngine implements IAsrEngine {

    private final DashScopeProperties props;

    /**
     * 根据 asr.model 创建对应 SDK 的实时识别会话
     *
     * @param config   会话配置（采样率、语言）
     * @param listener 识别结果与事件回调
     * @return AsrSession，API Key 未配置或创建失败时返回 null
     */
    @Override
    public AsrSession createSession(AsrSessionConfig config, AsrEventListener listener) {
        // 1. 校验 API Key
        String apiKey = props.getApiKey();
        if (!StringUtils.hasText(apiKey)) {
            listener.onError("DashScope api-key 未配置");
            return null;
        }

        // 2. 按配置的 model 路由到对应 SDK 适配器
        String model = props.getAsr().getModel();
        if (isOmniModel(model)) {
            log.info("ASR 路由 → OmniRealtime ({})", model);
            return OmniRealtimeAsrAdapter.createSession(props, config, listener);
        }
        if (isGummyChatModel(model)) {
            log.info("ASR 路由 → GummyChat ({})", model);
            return GummyChatAsrAdapter.createSession(props, config, listener);
        }
        if (isGummyModel(model)) {
            log.info("ASR 路由 → GummyRealtime ({})", model);
            return GummyRealtimeAsrAdapter.createSession(props, config, listener);
        }
        log.info("ASR 路由 → Recognition ({})", model);
        return RecognitionAsrAdapter.createSession(props, config, listener);
    }

    /**
     * 是否为 Omni/千问 ASR 模型（走 OmniRealtime API）
     *
     * @param m 模型名
     * @return 是否匹配
     */
    private static boolean isOmniModel(String m) {
        if (m == null) return false;
        String lower = m.toLowerCase();
        return lower.startsWith("qwen") && (lower.contains("asr") || lower.contains("omni"));
    }

    /**
     * 是否为 Gummy 一句话识别模型（走 GummyChat API）
     *
     * @param m 模型名
     * @return 是否匹配
     */
    private static boolean isGummyChatModel(String m) {
        return m != null && m.toLowerCase().startsWith("gummy-chat");
    }

    /**
     * 是否为 Gummy 实时流式模型（走 GummyRealtime API）
     *
     * @param m 模型名
     * @return 是否匹配
     */
    private static boolean isGummyModel(String m) {
        return m != null && m.toLowerCase().startsWith("gummy");
    }
}
