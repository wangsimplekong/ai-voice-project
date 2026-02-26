package com.ai.voice.engine.local;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.ai.voice.config.LocalVoiceProperties;
import com.ai.voice.engine.IAsrFileEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.time.Duration;

/**
 * 本地自部署文件 ASR 引擎
 * 调用 qwen-asr-serve 的 OpenAI 兼容 API: POST /v1/audio/transcriptions
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.voice.local.enabled", havingValue = "true")
public class LocalAsrFileEngine implements IAsrFileEngine {

    private final String asrUrl;
    private final RestTemplate restTemplate;

    public LocalAsrFileEngine(LocalVoiceProperties props) {
        this.asrUrl = props.getAsrUrl();
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
     * 调用本地 qwen-asr-serve 识别音频文件
     *
     * @param audioFile  音频文件
     * @param format     格式（wav/mp3 等）
     * @param sampleRate 采样率
     * @return 识别文本，失败返回 null
     */
    @Override
    public String recognizeFile(File audioFile, String format, int sampleRate) {
        // 1. 校验入参
        if (audioFile == null || !audioFile.exists()) return null;

        // 2. 构建 multipart 请求并调用本地 ASR 服务
        String url = asrUrl + "/v1/audio/transcriptions";
        log.debug("本地 ASR 文件识别: {} -> {}", audioFile.getName(), url);

        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new FileSystemResource(audioFile));
            body.add("model", "qwen3-asr");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                JSONObject json = JSON.parseObject(resp.getBody());
                String text = json.getString("text");
                log.debug("ASR 文件识别结果: {}", text);
                return text;
            }

            log.warn("ASR 文件识别返回非 200: {}", resp.getStatusCode());
            return null;
        } catch (Exception e) {
            log.error("本地 ASR 文件识别失败: {}", e.getMessage());
            return null;
        }
    }
}
