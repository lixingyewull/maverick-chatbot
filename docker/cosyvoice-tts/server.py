import os
import io
import sys
import shutil
import subprocess
from fastapi import FastAPI, Form
from fastapi.responses import Response
import numpy as np
import soundfile as sf
import torch
from utils import ensure_git_clone

app = FastAPI(title="CosyVoice TTS")

CODE_DIR = os.getenv("COSYVOICE_CODE_DIR", "/opt/CosyVoice")
MODEL_DIR = os.getenv("COSYVOICE_MODEL_DIR", "/models/CosyVoice2-0.5B")
AUTO = os.getenv("COSYVOICE_AUTO_DOWNLOAD", "true").lower() == "true"
CODE_GIT = os.getenv("COSYVOICE_CODE_GIT_URL", "").strip()
MODEL_GIT = os.getenv("COSYVOICE_MODEL_GIT_URL", "").strip()

cosy = None
sr = 16000

def ensure_resources():
    if AUTO:
        if CODE_GIT:
            ensure_git_clone(CODE_GIT, CODE_DIR)
        if MODEL_GIT:
            ensure_git_clone(MODEL_GIT, MODEL_DIR)

def _inject_third_party_paths():
    # 仅通过 sys.path 注入，不再尝试 pip 可编辑安装
    third_party = os.path.join(CODE_DIR, 'third_party')
    matcha_root = os.path.join(third_party, 'Matcha-TTS')
    matcha_src = os.path.join(matcha_root, 'src')
    # 优先插在最前，避免被环境中的同名包抢占
    for p in [CODE_DIR, third_party, matcha_src, matcha_root]:
        if os.path.isdir(p) and p not in sys.path:
            sys.path.insert(0, p)

def _find_model_root(root: str) -> str:
    # 寻找包含 cosyvoice.yaml 或 cosyvoice2.yaml 的目录
    wanted = { 'cosyvoice.yaml', 'cosyvoice2.yaml' }
    for cur, dirs, files in os.walk(root):
        if any(name in files for name in wanted):
            return cur
    return root

def load_cosyvoice():
    global cosy, sr
    ensure_resources()
    _inject_third_party_paths()
    # 动态导入 CosyVoice，避免安装在全局 site-packages
    import importlib.util
    import importlib
    cosyvoice_path = os.path.join(CODE_DIR, "cosyvoice")
    spec = importlib.util.spec_from_file_location("cosyvoice.entry", os.path.join(cosyvoice_path, "__init__.py"))
    if spec is None or spec.loader is None:
        raise RuntimeError("CosyVoice code not found: " + cosyvoice_path)

    # 只使用 CosyVoice2：尝试多种导入路径
    CosyVoice2 = None
    import_error: Exception | None = None
    try:
        from cosyvoice.cli.cosyvoice2 import CosyVoice2 as _CV2
        CosyVoice2 = _CV2
    except Exception as e:
        import_error = e
        try:
            from cosyvoice.cli.cosyvoice import CosyVoice2 as _CV2
            CosyVoice2 = _CV2
        except Exception as e2:
            import_error = e2
            # 扫描 cli 目录寻找 cosyvoice2*.py 动态加载
            cli_dir = os.path.join(CODE_DIR, 'cosyvoice', 'cli')
            try:
                for name in os.listdir(cli_dir):
                    if name.startswith('cosyvoice2') and name.endswith('.py'):
                        mod_path = os.path.join(cli_dir, name)
                        loader = importlib.machinery.SourceFileLoader('cosyvoice.cli._cosyvoice2_dyn', mod_path)
                        mod = importlib.util.module_from_spec(importlib.util.spec_from_loader(loader.name, loader))
                        loader.exec_module(mod)
                        if hasattr(mod, 'CosyVoice2'):
                            CosyVoice2 = getattr(mod, 'CosyVoice2')
                            break
            except Exception as e3:
                import_error = e3
    if CosyVoice2 is None:
        raise RuntimeError("无法导入 CosyVoice2，请检查代码仓是否包含 CosyVoice2，或设置 COSYVOICE_CODE_GIT_REF 指向包含 CosyVoice2 的分支/标签。原始错误: " + str(import_error))

    model_root = _find_model_root(MODEL_DIR)
    yaml_v2 = os.path.join(model_root, 'cosyvoice2.yaml')
    if not os.path.exists(yaml_v2):
        raise RuntimeError(f"未找到 cosyvoice2.yaml，模型目录: {model_root}")

    # 为 CosyVoice2 准备其内部硬编码的 LLM 目录名：CosyVoice-BlankEN
    # 有些版本会覆盖 qwen_pretrain_path = os.path.join(model_dir, 'CosyVoice-BlankEN')
    # 这里将其指向我们真实的 Qwen2-0.5B-Instruct 目录（可由 COSYVOICE_LLM_DIR 指定）
    llm_src = os.getenv('COSYVOICE_LLM_DIR', '/models/Qwen2-0.5B-Instruct')
    llm_alias = os.path.join(model_root, 'CosyVoice-BlankEN')
    try:
        if os.path.isdir(llm_src):
            if not os.path.exists(llm_alias):
                os.symlink(llm_src, llm_alias)
    except Exception:
        pass

    # 确认 matcha 可导入
    try:
        importlib.import_module('matcha')
    except Exception as e:
        raise RuntimeError("无法导入 third_party Matcha-TTS 包，请检查路径注入: " + str(e))

    cosy_local = CosyVoice2(model_root, load_jit=False, load_trt=False, load_vllm=False, fp16=False)
    cosy = cosy_local
    # CosyVoice 内部通常有 sample_rate 属性
    try:
        sr = getattr(cosy, 'sample_rate', 16000)
    except Exception:
        sr = 16000

@app.on_event("startup")
def on_startup():
    load_cosyvoice()

def synthesize(text: str, voice: str | None) -> bytes:
    # sft 推理，voice 例如 "中文女"；如未提供，则用模型默认
    spk = voice or "中文女"
    chunks = []
    for i, j in enumerate(cosy.inference_sft(text, spk, stream=False)):
        wav = j['tts_speech']  # Tensor [1, N]
        chunks.append(wav)
    if not chunks:
        audio = torch.zeros(1, int(sr * 1.0))
    else:
        audio = torch.cat(chunks, dim=1)
    buf = io.BytesIO()
    # 将 (1, N) Tensor 转为 numpy float32
    wav_np = audio.squeeze(0).detach().cpu().numpy().astype('float32')
    import soundfile as sf
    sf.write(buf, wav_np, sr, format='WAV')
    return buf.getvalue()

@app.post("/v1/tts:synthesize")
def tts_synthesize(text: str = Form(...), voice: str | None = Form(None)):
    if cosy is None:
        return Response(status_code=500, content=b"CosyVoice not initialized")
    try:
        wav = synthesize(text, voice)
        return Response(wav, media_type="audio/wav")
    except Exception as e:
        return Response(status_code=400, content=("TTS failed: " + str(e)).encode())


