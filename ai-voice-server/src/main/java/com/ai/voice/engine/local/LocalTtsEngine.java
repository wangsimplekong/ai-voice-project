package com.ai.voice.engine.local;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.ai.voice.config.LocalVoiceProperties;
import com.ai.voice.engine.ITtsEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 本地自部署 TTS 引擎
 * 调用 tts_server.py 的 OpenAI 兼容 API: POST /v1/audio/speech
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.voice.local.enabled", havingValue = "true")
public class LocalTtsEngine implements ITtsEngine {

    private final LocalVoiceProperties props;
    private final RestTemplate restTemplate;

    public LocalTtsEngine(LocalVoiceProperties props) {
        this.props = props;
        this.restTemplate = createRestTemplate(props.getTimeoutMs());
    }

    /**
     * 创建带连接/读取超时的 RestTemplate
     *
     * @param timeoutMs 连接与读取超时时间（毫秒）
     * @return 配置好超时的 RestTemplate
     */
    private static RestTemplate createRestTemplate(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return new RestTemplate(factory);
    }

    /**
     * 调用本地 TTS 服务合成语音
     *
     * @param text  待合成文本
     * @param voice 音色（空则使用配置默认）
     * @return 音频字节数组，失败返回 null
     */
    @Override
    public byte[] synthesize(String text, String voice) {
        // 1. 校验入参
        if (!StringUtils.hasText(text)) return null;

        // 2. 构建请求并调用本地 TTS 服务
        String url = props.getTtsUrl() + "/v1/audio/speech";
        log.debug("本地 TTS 合成: voice={}, text={}", voice, text.substring(0, Math.min(text.length(), 50)));

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("input", text);
            body.put("voice", StringUtils.hasText(voice) ? voice : props.getTtsVoice());
            body.put("language", props.getTtsLanguage());
            body.put("response_format", props.getTtsFormat());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<byte[]> resp = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(JSON.toJSONString(body), headers),
                    byte[].class);

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                log.debug("TTS 合成成功, 音频大小: {} bytes", resp.getBody().length);
                return resp.getBody();
            }

            log.warn("TTS 合成返回非 200: {}", resp.getStatusCode());
            return null;
        } catch (HttpClientErrorException e) {
            String body = e.getResponseBodyAsString();
            String msg = parseTtsErrorBody(body, StringUtils.hasText(voice) ? voice : props.getTtsVoice(), e.getStatusCode().value());
            log.error("本地 TTS 合成失败: {}", msg);
            return null;
        } catch (Exception e) {
            log.error("本地 TTS 合成失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析 TTS 错误响应，输出可读提示（如音色不支持）
     */
    private String parseTtsErrorBody(String body, String requestedVoice, int statusCode) {
        if (body == null || body.isEmpty()) {
            return statusCode + " 请求失败";
        }
        try {
            JSONObject json = JSON.parseObject(body);
            String detail = json.getString("detail");
            if (detail != null && detail.contains("Unsupported speakers") && detail.contains("Supported:")) {
                return String.format("当前音色 [%s] 不被本地 TTS 支持。请在配置中设置 ai.voice.local.tts-voice 或在请求中传入支持的音色之一。支持的音色: %s", requestedVoice, detail.substring(detail.indexOf("Supported:")));
            }
            if (detail != null) {
                return "本地 TTS 请求失败: " + detail;
            }
        } catch (Exception ignored) { }
        return statusCode + " " + body;
    }
}
