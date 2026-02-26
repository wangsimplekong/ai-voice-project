package com.ai.voice.service;

public interface IAsrFileService {

    /**
     * 音频文件语音识别（含 LLM 纠错）
     *
     * @param audioBytes  音频文件字节数组
     * @param contentType 文件 MIME 类型（audio/wav、audio/mp3 等）
     * @return 识别并纠错后的文本，失败返回 null
     */
    String recognizeFromFile(byte[] audioBytes, String contentType);
}
