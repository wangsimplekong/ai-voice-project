"""
Qwen3-ASR 实时流式 WebSocket 服务（VAD + 转写）

协议匹配 Java AsrRealtimeHandler:
  客户端 → 服务端: 二进制 PCM (16kHz/16bit/mono) | {"audio":"base64_pcm"} | {"action":"end"}
  服务端 → 客户端: {"text":"..","sentenceEnd":false,"final":false}
                   {"text":"..","sentenceEnd":true,"final":false}
                   {"text":"","sentenceEnd":true,"final":true}
                   {"error":".."}

启动: python asr_realtime_server.py --asr-url http://localhost:8003 --port 8005
"""

import argparse
import asyncio
import base64
import io
import json
import logging
import wave
from typing import Optional

import httpx
import numpy as np
import webrtcvad
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from starlette.websockets import WebSocketState

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("asr-realtime")

# --------------- Config (overridden by CLI args) ---------------

ASR_API_URL = "http://localhost:8003"
ASR_MODEL_NAME = "qwen3-asr"

SAMPLE_RATE = 16000
CHANNELS = 1
SAMPLE_WIDTH = 2  # 16-bit

FRAME_DURATION_MS = 30
FRAME_BYTES = int(SAMPLE_RATE * FRAME_DURATION_MS / 1000) * SAMPLE_WIDTH  # 960 bytes per 30ms

VAD_AGGRESSIVENESS = 2        # 0(宽松) ~ 3(激进)
SILENCE_DURATION_MS = 600     # 静音多久判定句尾
PARTIAL_INTERVAL_S = 1.5      # 语音期间每隔多久出一次中间结果
MIN_SPEECH_MS = 200           # 最短语音长度（过滤噪声误触发）

SPEECH_PAD_MS = 150           # 句尾额外保留的音频（避免截断尾音）

app = FastAPI(title="ASR Realtime WebSocket")


# --------------- PCM → WAV ---------------

def pcm_to_wav(pcm: bytes) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(CHANNELS)
        wf.setsampwidth(SAMPLE_WIDTH)
        wf.setframerate(SAMPLE_RATE)
        wf.writeframes(pcm)
    buf.seek(0)
    return buf.read()


# --------------- ASR Session ---------------

class AsrRealtimeSession:

    def __init__(self, ws: WebSocket, asr_url: str):
        self.ws = ws
        self.asr_url = asr_url
        self.vad = webrtcvad.Vad(VAD_AGGRESSIVENESS)

        self.frame_buf = bytearray()
        self.speech_buf = bytearray()
        self.is_speaking = False
        self.silence_frames = 0
        self.speech_frames = 0

        self.silence_threshold = SILENCE_DURATION_MS // FRAME_DURATION_MS
        self.min_speech_frames = MIN_SPEECH_MS // FRAME_DURATION_MS
        self.pad_frames = SPEECH_PAD_MS // FRAME_DURATION_MS

        self.last_partial_bytes = 0
        self.partial_interval_bytes = int(PARTIAL_INTERVAL_S * SAMPLE_RATE * SAMPLE_WIDTH)

        self.sentence_texts = []
        self.http = httpx.AsyncClient(timeout=30)
        self._closed = False

    async def feed_audio(self, pcm: bytes):
        """接收 PCM 音频数据，内部做 VAD 和转写"""
        self.frame_buf.extend(pcm)

        while len(self.frame_buf) >= FRAME_BYTES:
            frame = bytes(self.frame_buf[:FRAME_BYTES])
            self.frame_buf = self.frame_buf[FRAME_BYTES:]

            try:
                is_speech = self.vad.is_speech(frame, SAMPLE_RATE)
            except Exception:
                is_speech = True

            if is_speech:
                if not self.is_speaking:
                    self.is_speaking = True
                    self.silence_frames = 0
                    self.speech_frames = 0
                    self.last_partial_bytes = 0
                    logger.debug("Speech start detected")

                self.speech_buf.extend(frame)
                self.speech_frames += 1
                self.silence_frames = 0

                grown = len(self.speech_buf) - self.last_partial_bytes
                if grown >= self.partial_interval_bytes:
                    await self._send_partial()

            else:
                if self.is_speaking:
                    self.speech_buf.extend(frame)
                    self.silence_frames += 1

                    if self.silence_frames >= self.silence_threshold:
                        if self.speech_frames >= self.min_speech_frames:
                            await self._send_sentence_end()
                        else:
                            logger.debug("Speech too short (%d ms), discarded",
                                         self.speech_frames * FRAME_DURATION_MS)
                        self._reset_segment()

    async def end_session(self):
        """客户端主动结束会话"""
        if self.is_speaking and len(self.speech_buf) > 0:
            if self.speech_frames >= self.min_speech_frames:
                await self._send_sentence_end()
            self._reset_segment()

        await self._push(text="", sentence_end=True, final=True)

    async def close(self):
        if not self._closed:
            self._closed = True
            await self.http.aclose()

    # --------------- Internal ---------------

    async def _send_partial(self):
        """发送中间结果（语音进行中）"""
        text = await self._transcribe(bytes(self.speech_buf))
        self.last_partial_bytes = len(self.speech_buf)
        if text:
            await self._push(text=text, sentence_end=False, final=False)

    async def _send_sentence_end(self):
        """VAD 检测到句尾，发送最终句子结果"""
        text = await self._transcribe(bytes(self.speech_buf))
        if text:
            self.sentence_texts.append(text.strip())
            await self._push(text=text, sentence_end=True, final=False)
            logger.info("Sentence: %s", text.strip())

    async def _transcribe(self, pcm: bytes) -> Optional[str]:
        """调用 qwen-asr-serve 进行转写"""
        if len(pcm) < FRAME_BYTES:
            return None
        try:
            wav_data = pcm_to_wav(pcm)
            resp = await self.http.post(
                f"{self.asr_url}/v1/audio/transcriptions",
                files={"file": ("audio.wav", wav_data, "audio/wav")},
                data={"model": ASR_MODEL_NAME},
            )
            if resp.status_code == 200:
                result = resp.json()
                return result.get("text", "")
            else:
                logger.warning("ASR API returned %d: %s", resp.status_code, resp.text[:200])
        except Exception as e:
            logger.error("Transcription failed: %s", e)
        return None

    async def _push(self, text: str, sentence_end: bool, final: bool):
        if self._closed:
            return
        try:
            if self.ws.client_state == WebSocketState.CONNECTED:
                await self.ws.send_json({
                    "text": text or "",
                    "sentenceEnd": sentence_end,
                    "final": final,
                })
        except Exception:
            pass

    def _reset_segment(self):
        self.speech_buf = bytearray()
        self.is_speaking = False
        self.silence_frames = 0
        self.speech_frames = 0
        self.last_partial_bytes = 0


# --------------- WebSocket Endpoint ---------------

@app.websocket("/ws/asr")
async def websocket_asr(ws: WebSocket):
    await ws.accept()
    session = AsrRealtimeSession(ws, ASR_API_URL)
    logger.info("Session opened")

    try:
        while True:
            msg = await ws.receive()

            if msg.get("type") == "websocket.disconnect":
                break

            if "bytes" in msg and msg["bytes"]:
                await session.feed_audio(msg["bytes"])

            elif "text" in msg and msg["text"]:
                try:
                    obj = json.loads(msg["text"])
                    if obj.get("action") == "end":
                        await session.end_session()
                        break
                    audio_b64 = obj.get("audio")
                    if audio_b64:
                        await session.feed_audio(base64.b64decode(audio_b64))
                except (json.JSONDecodeError, Exception) as e:
                    try:
                        await ws.send_json({"error": str(e)})
                    except Exception:
                        pass

    except WebSocketDisconnect:
        logger.info("Client disconnected")
    except Exception as e:
        logger.exception("Session error")
        try:
            await ws.send_json({"error": str(e)})
        except Exception:
            pass
    finally:
        if session.is_speaking and len(session.speech_buf) > 0:
            try:
                await session.end_session()
            except Exception:
                pass
        await session.close()
        logger.info("Session closed")


@app.get("/health")
async def health():
    return {"status": "ok", "service": "asr-realtime"}


# --------------- Main ---------------

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="ASR Realtime WebSocket Server")
    parser.add_argument("--asr-url", type=str, default="http://localhost:8003",
                        help="qwen-asr-serve 地址")
    parser.add_argument("--host", type=str, default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8005)
    parser.add_argument("--vad-aggressiveness", type=int, default=2, choices=[0, 1, 2, 3],
                        help="VAD 灵敏度 0(宽松)~3(激进)")
    parser.add_argument("--silence-ms", type=int, default=600,
                        help="静音多少毫秒判定句尾")
    parser.add_argument("--partial-interval", type=float, default=1.5,
                        help="中间结果发送间隔(秒)")
    args = parser.parse_args()

    ASR_API_URL = args.asr_url
    VAD_AGGRESSIVENESS = args.vad_aggressiveness
    SILENCE_DURATION_MS = args.silence_ms
    PARTIAL_INTERVAL_S = args.partial_interval

    import uvicorn
    uvicorn.run(app, host=args.host, port=args.port)
