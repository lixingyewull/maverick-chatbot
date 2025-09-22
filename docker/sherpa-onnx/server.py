import io
import os
import numpy as np
import soundfile as sf
import librosa
from fastapi import FastAPI, UploadFile, File, HTTPException
from fastapi.responses import JSONResponse
import sherpa_onnx as so
import tempfile
from utils import get_github_asset_url, download_and_extract, check_and_extract_local_file

app = FastAPI(title="sherpa-onnx HTTP ASR")

MODEL_DIR = os.getenv("SHERPA_MODEL_DIR", "/models")
TOKENS = os.path.join(MODEL_DIR, "tokens.txt")
NUM_THREADS = int(os.getenv("SHERPA_NUM_THREADS", "2"))
PROVIDER = os.getenv("SHERPA_PROVIDER", "cpu")
DECODING = os.getenv("SHERPA_DECODING", "greedy_search")

ENCODER = os.path.join(MODEL_DIR, "encoder.onnx")
DECODER = os.path.join(MODEL_DIR, "decoder.onnx")
JOINER  = os.path.join(MODEL_DIR, "joiner.onnx")
SENSE_VOICE_MODEL = os.path.join(MODEL_DIR, "model.onnx")

AUTO_DOWNLOAD = os.getenv("SHERPA_AUTO_DOWNLOAD", "false").lower() == "true"
MODEL_BUNDLE_URL = os.getenv("SHERPA_MODEL_BUNDLE_URL", "").strip()
URL_ENCODER = os.getenv("SHERPA_URL_ENCODER", "").strip()
URL_DECODER = os.getenv("SHERPA_URL_DECODER", "").strip()
URL_JOINER = os.getenv("SHERPA_URL_JOINER", "").strip()
URL_TOKENS = os.getenv("SHERPA_URL_TOKENS", "").strip()

# GitHub Releases 选项（可选）：例如 owner=k2-fsa repo=sherpa-onnx tag=asr-models asset_prefix=sherpa-onnx-sense-voice-
GITHUB_OWNER = os.getenv("SHERPA_GH_OWNER", "").strip()
GITHUB_REPO = os.getenv("SHERPA_GH_REPO", "").strip()
GITHUB_TAG = os.getenv("SHERPA_GH_TAG", "").strip()
GITHUB_ASSET_PREFIX = os.getenv("SHERPA_GH_ASSET_PREFIX", "").strip()

recognizer = None

def _try_locate_and_link(dest_dir: str):
    # 在 dest_dir 内递归查找 encoder/decoder/joiner/tokens/model.onnx，如存在，则复制到根目录
    need = {
        'encoder.onnx': ENCODER,
        'decoder.onnx': DECODER,
        'joiner.onnx': JOINER,
        'tokens.txt': TOKENS,
        'model.onnx': SENSE_VOICE_MODEL,
    }
    found = {k: None for k in need.keys()}
    for root, _, files in os.walk(dest_dir):
        for f in files:
            if f in need and found[f] is None:
                found[f] = os.path.join(root, f)
    for name, target in need.items():
        if not os.path.exists(target) and found.get(name):
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(found[name], 'rb') as src, open(target, 'wb') as dst:
                dst.write(src.read())

def ensure_models():
    all_present_transducer = all(os.path.exists(p) for p in [ENCODER, DECODER, JOINER, TOKENS])
    sense_voice_present = os.path.exists(SENSE_VOICE_MODEL) and os.path.exists(TOKENS)
    if all_present_transducer or sense_voice_present:
        return
    if not AUTO_DOWNLOAD:
        raise RuntimeError(
            "缺少模型文件，且未开启自动下载。请挂载 /models 或设置 SHERPA_AUTO_DOWNLOAD=true 并提供下载地址"
        )

    # 1) 优先：打包模型链接（zip/tar.gz/tar.bz2/tgz）
    if MODEL_BUNDLE_URL:
        download_and_extract(MODEL_BUNDLE_URL, MODEL_DIR)
        _try_locate_and_link(MODEL_DIR)
        return

    # 2) 其次：GitHub Releases 自动发现资产
    if GITHUB_OWNER and GITHUB_REPO and GITHUB_TAG and GITHUB_ASSET_PREFIX:
        url = get_github_asset_url(GITHUB_OWNER, GITHUB_REPO, GITHUB_TAG, GITHUB_ASSET_PREFIX)
        if not url:
            raise RuntimeError("未从 GitHub Releases 获取到匹配资产直链")
        local = check_and_extract_local_file(url, MODEL_DIR)
        if local is None:
            download_and_extract(url, MODEL_DIR)
        _try_locate_and_link(MODEL_DIR)
        return

    # 3) 最后：分别提供四个文件 URL
    if not (URL_ENCODER and URL_DECODER and URL_JOINER and URL_TOKENS):
        raise RuntimeError(
            "未提供 SHERPA_MODEL_BUNDLE_URL/GitHub 信息，且四个单文件URL不完整，请设置 SHERPA_URL_ENCODER/DECODER/JOINER/TOKENS"
        )
    download_and_extract(URL_ENCODER, MODEL_DIR)
    download_and_extract(URL_DECODER, MODEL_DIR)
    download_and_extract(URL_JOINER, MODEL_DIR)
    download_and_extract(URL_TOKENS, MODEL_DIR)
    _try_locate_and_link(MODEL_DIR)

def init_recognizer():
    global recognizer
    ensure_models()

    # 优先使用 transducer 三件套；否则尝试 sense-voice 单模型
    if os.path.exists(ENCODER) and os.path.exists(DECODER) and os.path.exists(JOINER):
        model_cfg = so.OfflineModelConfig(
            transducer=so.OfflineTransducerModelConfig(
                ENCODER,
                DECODER,
                JOINER,
            ),
            tokens=TOKENS,
            num_threads=NUM_THREADS,
            provider=PROVIDER,
        )
        recog_cfg = so.OfflineRecognizerConfig(
            model=model_cfg,
            decoding_method=DECODING,
        )
        print("[sherpa-onnx] loading transducer models from", MODEL_DIR)
        recognizer = so.OfflineRecognizer(recog_cfg)
        return

    if os.path.exists(SENSE_VOICE_MODEL):
        print("[sherpa-onnx] loading sense-voice model from", SENSE_VOICE_MODEL)
        recognizer = so.OfflineRecognizer.from_sense_voice(
            model=SENSE_VOICE_MODEL,
            tokens=TOKENS,
            num_threads=NUM_THREADS,
            use_itn=True,
            debug=False,
            provider=PROVIDER,
        )
        return

    raise RuntimeError("未找到可用的模型文件：缺少 transducer(encoder/decoder/joiner) 或 sense-voice(model.onnx)")

@app.on_event("startup")
def on_startup():
    init_recognizer()

@app.post("/v1/asr:transcribe")
async def transcribe(file: UploadFile = File(...)):
    if recognizer is None:
        raise HTTPException(status_code=500, detail="Recognizer 未初始化")
    try:
        raw = await file.read()
        if not raw or len(raw) == 0:
            raise ValueError("空文件")

        # 先将上传内容写入临时文件，再用 librosa.load 解码，确保兼容 mp3/m4a/aac 等
        suffix = os.path.splitext(file.filename or "")[1] or ".wav"
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
            tmp.write(raw)
            tmp_path = tmp.name

        try:
            # librosa 会自动解码为 float32/float64，sr 强制重采样到 16k，mono=True 转单声道
            audio, sr = librosa.load(tmp_path, sr=16000, mono=True)
            if audio is None or audio.size == 0:
                raise ValueError("无法解码音频")
            if audio.dtype != np.float32:
                audio = audio.astype(np.float32, copy=False)
        finally:
            try:
                os.unlink(tmp_path)
            except Exception:
                pass

        stream = recognizer.create_stream()
        stream.accept_waveform(16000, audio)
        recognizer.decode_streams([stream])
        # 兼容不同版本API：优先从 stream.result 取文本，否则使用 get_result
        text = getattr(getattr(stream, 'result', None), 'text', None)
        if not text:
            result = recognizer.get_result(stream)
            text = getattr(result, 'text', '')
        return JSONResponse({"text": text or ""})
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"转写失败: {e}")


