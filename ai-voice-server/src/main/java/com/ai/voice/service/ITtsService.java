package com.ai.voice.service;

import com.ai.voice.dto.TtsSynthesizeDTO;

public interface ITtsService {

    /**
     * 文本转语音合成
     *
     * @param dto 合成参数（文本内容、音色等）
     * @return 音频字节数组（MP3/WAV），合成失败返回 null
     */
    byte[] synthesize(TtsSynthesizeDTO dto);
}
