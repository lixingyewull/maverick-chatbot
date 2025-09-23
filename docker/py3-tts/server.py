import tempfile
import subprocess
import pyttsx3
from fastapi import FastAPI, Form
from fastapi.responses import Response, JSONResponse

app = FastAPI(title="py3-tts (offline)")

engine = pyttsx3.init()

def synth_wav(text: str, voice: str | None) -> bytes:
    # 选择语音：优先 pyttsx3 匹配 name/id/languages，若声称中文或未匹配则回退 espeak-ng
    force_espeak = False
    if voice:
        v_lower = voice.lower()
        if v_lower.startswith('zh') or v_lower in ('chinese', 'cmn', 'yue'):
            force_espeak = True
        else:
            try:
                for v in engine.getProperty('voices'):
                    name = (getattr(v, 'name', '') or '').lower()
                    vid = (getattr(v, 'id', '') or '').lower()
                    langs = getattr(v, 'languages', []) or []
                    langs_join = ' '.join([str(x) for x in langs]).lower()
                    if v_lower in name or v_lower in vid or 'zh' in langs_join or 'chinese' in name:
                        engine.setProperty('voice', v.id)
                        break
            except Exception:
                pass
    with tempfile.NamedTemporaryFile(suffix='.wav') as f:
        # 若强制 espeak，直接使用 espeak 生成
        if force_espeak:
            cmd = [
                "espeak-ng",
                "-w", f.name,
            ]
            # 语种/音色
            if voice:
                cmd.extend(["-v", voice])
            cmd.append(text)
            subprocess.run(cmd, check=True)
            f.seek(0)
            data = f.read()
            return data
        # 首选 pyttsx3
        engine.save_to_file(text, f.name)
        engine.runAndWait()
        f.seek(0)
        data = f.read()
        # 回退：若未生成有效音频（或强制中文但未匹配到中文 voice），使用 espeak-ng 生成 WAV
        if data is None or len(data) < 100:
            cmd = [
                "espeak-ng",
                "-w", f.name,
            ]
            if voice:
                cmd.extend(["-v", voice])
            else:
                cmd.extend(["-v", "zh"])  # 默认中文
            cmd.append(text)
            subprocess.run(cmd, check=True)
            f.seek(0)
            data = f.read()
        return data

@app.post("/v1/tts-synthesize")
def tts_synthesize(text: str = Form(...), voice: str | None = Form(None)):
    try:
        data = synth_wav(text, voice)
        return Response(data, media_type="audio/wav")
    except Exception as e:
        return Response(status_code=400, content=("TTS failed: " + str(e)).encode())

@app.get("/v1/tts-voices")
def list_voices():
    try:
        vs = []
        for v in engine.getProperty('voices'):
            vs.append({
                'id': getattr(v, 'id', ''),
                'name': getattr(v, 'name', ''),
                'languages': [str(x) for x in (getattr(v, 'languages', []) or [])],
            })
        return JSONResponse(vs)
    except Exception as e:
        return JSONResponse(status_code=500, content={'error': str(e)})


