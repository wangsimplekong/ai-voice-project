package com.ai.voice.engine;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 实时 ASR 会话配置
 * 用于创建会话时传入采样率、语言等参数，本地引擎当前多由服务端默认，DashScope 引擎会使用
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsrSessionConfig {

    /** 音频采样率（Hz），默认 16000 */
    @Builder.Default
    private int sampleRate = 16000;

    /** 识别语言，如 zh / en，默认 zh */
    @Builder.Default
    private String language = "zh";
}
