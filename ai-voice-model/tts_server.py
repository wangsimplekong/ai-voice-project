"""
Qwen3-TTS FastAPI Server (OpenAI-compatible /v1/audio/speech)
启动方式: CUDA_VISIBLE_DEVICES=2 python tts_server.py --model Qwen/Qwen3-TTS-12Hz-1.7B-CustomVoice --port 8004
"""

import argparse
import io
import logging
import subprocess
import sys
from contextlib import asynccontextmanager
from typing import Optional

import numpy as np
import soundfile as sf
import torch
from fastapi import FastAPI, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel, Field

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("tts-server")

# --------------- Global State ---------------

_model = None
_model_path: str = ""


def get_model():
    if _model is None:
        raise HTTPException(status_code=503, detail="模型尚未加载完成")
    return _model


# --------------- Request / Response Schema ---------------

class SpeechRequest(BaseModel):
    model: str = "qwen3-tts"
    input: str = Field(..., min_length=1, max_length=20000, description="待合成文本")
    voice: str = Field(default="", description="说话人ID (CustomVoice 模型)")
    language: str = Field(default="Auto", description="语言: Auto/Chinese/English/Japanese/...")
    instruct: Optional[str] = Field(default=None, description="风格指令 (仅 1.7B CustomVoice 支持)")
    response_format: str = Field(default="wav", description="输出格式: wav / mp3")


class VoiceItem(BaseModel):
    voice_id: str
    name: str


# --------------- Audio Encoding ---------------

def encode_wav(audio: np.ndarray, sr: int) -> bytes:
    buf = io.BytesIO()
    sf.write(buf, audio, sr, format="WAV", subtype="PCM_16")
    buf.seek(0)
    return buf.read()


def encode_mp3(audio: np.ndarray, sr: int) -> bytes:
    pcm_buf = io.BytesIO()
    sf.write(pcm_buf, audio, sr, format="WAV", subtype="PCM_16")
    pcm_buf.seek(0)
    proc = subprocess.run(
        ["ffmpeg", "-i", "pipe:0", "-f", "mp3", "-ab", "192k", "-v", "quiet", "pipe:1"],
        input=pcm_buf.read(), capture_output=True,
    )
    if proc.returncode != 0:
        raise HTTPException(status_code=500, detail="ffmpeg MP3 编码失败，请确认系统已安装 ffmpeg")
    return proc.stdout


# --------------- FastAPI App ---------------

@asynccontextmanager
async def lifespan(app: FastAPI):
    global _model
    from qwen_tts.inference.qwen3_tts_model import Qwen3TTSModel

    logger.info("加载 Qwen3-TTS 模型: %s ...", _model_path)
    _model = Qwen3TTSModel.from_pretrained(
        _model_path,
        device_map="cuda:0",
        torch_dtype=torch.bfloat16,
    )
    speakers = _model.get_supported_speakers()
    logger.info("模型加载完成。支持说话人: %s", speakers)
    yield
    logger.info("服务关闭。")


app = FastAPI(title="Qwen3-TTS Server", lifespan=lifespan)


@app.post("/v1/audio/speech")
async def create_speech(req: SpeechRequest):
    model = get_model()

    try:
        kwargs = dict(
            text=req.input,
            speaker=req.voice if req.voice else None,
            language=req.language,
        )
        if req.instruct:
            kwargs["instruct"] = req.instruct

        wavs, sr = model.generate_custom_voice(**kwargs)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.exception("TTS 推理异常")
        raise HTTPException(status_code=500, detail=f"推理失败: {e}")

    audio_data = wavs[0]
    fmt = req.response_format.lower()

    if fmt == "mp3":
        content = encode_mp3(audio_data, sr)
        media_type = "audio/mpeg"
        filename = "speech.mp3"
    else:
        content = encode_wav(audio_data, sr)
        media_type = "audio/wav"
        filename = "speech.wav"

    return Response(
        content=content,
        media_type=media_type,
        headers={"Content-Disposition": f"attachment; filename={filename}"},
    )


@app.get("/v1/audio/voices")
async def list_voices():
    model = get_model()
    speakers = model.get_supported_speakers() or []
    return {
        "voices": [{"voice_id": s, "name": s} for s in speakers],
    }


@app.get("/health")
async def health():
    return {
        "status": "ok" if _model is not None else "loading",
        "model": _model_path,
    }


# --------------- Main ---------------

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Qwen3-TTS Server")
    parser.add_argument("--model", type=str, default="Qwen/Qwen3-TTS-12Hz-1.7B-CustomVoice")
    parser.add_argument("--host", type=str, default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8004)
    parser.add_argument("--workers", type=int, default=1)
    args = parser.parse_args()

    _model_path = args.model

    import uvicorn
    uvicorn.run(app, host=args.host, port=args.port, workers=args.workers)
