import base64
import os
import sys
import json
import requests
try:
    import yaml
except Exception:
    yaml = None


host = "https://openspeech.bytedance.com"


def train(appid, token, audio_path, spk_id, auth_header_prefix="Bearer; "):
    url = host + "/api/v1/mega_tts/audio/upload"
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"{auth_header_prefix}{token}",
        "Resource-Id": "volc.megatts.voiceclone",
    }
    encoded_data, audio_format = encode_audio_file(audio_path)
    audios = [{"audio_bytes": encoded_data, "audio_format": audio_format}]
    data = {"appid": appid, "speaker_id": spk_id, "audios": audios, "source": 2,"language": 0, "model_type": 1}
    # 额外参数
    extra_params = {}
    if extra_params:
        data["extra_params"] =  json.dumps(extra_params)
    response = requests.post(url, json=data, headers=headers)
    print("status code = ", response.status_code)
    if response.status_code != 200:
        raise Exception("train请求错误:" + response.text)
    print("headers = ", response.headers)
    print(response.json())


def get_status(appid, token, spk_id, auth_header_prefix="Bearer; "):
    url = host + "/api/v1/mega_tts/status"
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"{auth_header_prefix}{token}",
        "Resource-Id": "volc.megatts.voiceclone",
    }
    body = {"appid": appid, "speaker_id": spk_id}
    response = requests.post(url, headers=headers, json=body)
    print(response.json())


def encode_audio_file(file_path):
    with open(file_path, 'rb') as audio_file:
        audio_data = audio_file.read()
        encoded_data = str(base64.b64encode(audio_data), "utf-8")
        audio_format = os.path.splitext(file_path)[1][1:]  # 获取文件扩展名作为音频格式
        return encoded_data, audio_format


def find_repo_root(start_dir=None):
    d = os.path.abspath(start_dir or os.path.dirname(__file__))
    while True:
        if os.path.exists(os.path.join(d, 'pom.xml')):
            return d
        parent = os.path.dirname(d)
        if parent == d:
            return os.path.abspath(os.getcwd())
        d = parent

def load_application_config(repo_root):
    if yaml is None:
        raise RuntimeError("缺少 PyYAML 依赖，请先: pip install pyyaml")
    candidates = [
        os.path.join(repo_root, 'src/main/resources/application-local.yml'),
        os.path.join(repo_root, 'src/main/resources/application.yml'),
    ]
    cfg = None
    for p in candidates:
        if os.path.exists(p):
            with open(p, 'r', encoding='utf-8') as f:
                cfg = yaml.safe_load(f)
            break
    if not cfg:
        raise FileNotFoundError("未找到 application 配置文件: application-local.yml / application.yml")
    volc = (((cfg.get('tts') or {}).get('volc')) or {})
    appid = volc.get('app-id')
    token = volc.get('access-token')
    auth_prefix = volc.get('auth-header-prefix', 'Bearer; ')
    if not appid or not token:
        raise RuntimeError("缺少 tts.volc.app-id 或 tts.volc.access-token 配置")
    # 强制转为字符串，避免 YAML 将大整数解析为数字导致服务端类型错误
    return str(appid), str(token), str(auth_prefix or 'Bearer; ')

def load_roles(repo_root):
    if yaml is None:
        raise RuntimeError("缺少 PyYAML 依赖，请先: pip install pyyaml")
    roles_path = os.path.join(repo_root, 'src/main/resources/roles/roles.yaml')
    if not os.path.exists(roles_path):
        raise FileNotFoundError("未找到 roles.yaml: " + roles_path)
    with open(roles_path, 'r', encoding='utf-8') as f:
        data = yaml.safe_load(f)
    if not isinstance(data, list):
        raise RuntimeError("roles.yaml 格式错误，应为列表")
    return data

def resolve_path(repo_root, p):
    if not p:
        return p
    if os.path.isabs(p):
        return p
    return os.path.join(repo_root, p)

if __name__ == "__main__":
    repo_root = find_repo_root()
    appid, token, auth_prefix = load_application_config(repo_root)
    roles = load_roles(repo_root)

    # 可选：支持命令行过滤 roleId
    only_role_id = sys.argv[1] if len(sys.argv) > 1 else None

    # 全局去重：同一个 spk_id 只上传一次
    seen_spk_ids = set()

    for role in roles:
        rid = role.get('id')
        if only_role_id and rid != only_role_id:
            continue
        samples = role.get('voiceSamples') or []
        if not samples:
            continue
        for i, s in enumerate(samples):
            spk_id = s.get('spk_id') or s.get('spkId')
            audio_path = resolve_path(repo_root, s.get('audio_path') or s.get('audioPath'))
            if not spk_id or not audio_path:
                print(f"跳过无效样本: role={rid} index={i}")
                continue
            if not os.path.exists(audio_path):
                print(f"[VoiceClone] 音频不存在，跳过: role={rid}, spk_id={spk_id}, path={audio_path}")
                continue
            sid = str(spk_id).strip()
            if sid in seen_spk_ids:
                print(f"[VoiceClone] 跳过重复 spk_id: {sid} (role={rid}, index={i})")
                continue
            seen_spk_ids.add(sid)
            print(f"[VoiceClone] 上传: role={rid}, spk_id={spk_id}, audio={audio_path}")
            train(appid=appid, token=token, audio_path=audio_path, spk_id=spk_id, auth_header_prefix=auth_prefix)
            print(f"[VoiceClone] 查询状态: role={rid}, spk_id={spk_id}")
            get_status(appid=appid, token=token, spk_id=spk_id, auth_header_prefix=auth_prefix)
    