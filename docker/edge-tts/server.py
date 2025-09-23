import io
import asyncio
from fastapi import FastAPI, Form
from fastapi.responses import Response
import edge_tts
import soundfile as sf
import os
app = FastAPI(title="Edge TTS")

DEFAULT_VOICE = "zh-CN-XiaoyanNeural"
DEFAULT_RATE = "+0%"
DEFAULT_VOLUME = "+0%"
PROXY = os.getenv('HTTPS_PROXY') or os.getenv('HTTP_PROXY') or None
async def synth_edge(text: str, voice: str | None) -> bytes:
    communicate = edge_tts.Communicate(
        text,
        voice or DEFAULT_VOICE,
        rate=DEFAULT_RATE,
        volume=DEFAULT_VOLUME,
        proxy=PROXY,
    )
    # edge-tts 返回的是分片的音频（mp3/ogg opus），这里直接拼接为 bytes
    buf = io.BytesIO()
    async for chunk in communicate.stream():
        if chunk["type"] == "audio":
            buf.write(chunk["data"])  # already audio bytes (mp3 by default)
    return buf.getvalue()

@app.post("/v1/tts-synthesize")
async def tts_synthesize(text: str = Form(...), voice: str | None = Form(None)):
    try:
        audio_bytes = await synth_edge(text, voice)
        # 返回 MP3（edge-tts 默认编码）
        return Response(audio_bytes, media_type="audio/mpeg")
    except Exception as e:
        return Response(status_code=400, content=("TTS failed: " + str(e)).encode())


