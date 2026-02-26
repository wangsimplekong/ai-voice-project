package com.ai.voice.service.impl;

import com.ai.voice.dto.TtsSynthesizeDTO;
import com.ai.voice.engine.ITtsEngine;
import com.ai.voice.service.ITtsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * TTS 服务实现：委托引擎合成，仅做入参校验
 */
@Service
@RequiredArgsConstructor
public class TtsServiceImpl implements ITtsService {

    private final ITtsEngine ttsEngine;

    /**
     * 文本转语音，委托当前注入的 TTS 引擎合成
     *
     * @param dto 合成参数（文本、音色）
     * @return 音频字节数组，失败返回 null
     */
    @Override
    public byte[] synthesize(TtsSynthesizeDTO dto) {
        // 1. 校验入参
        if (dto == null || !StringUtils.hasText(dto.getText())) return null;
        // 2. 委托引擎合成音频
        return ttsEngine.synthesize(dto.getText(), dto.getVoice());
    }
}
