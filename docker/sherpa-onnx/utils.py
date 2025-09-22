import os
import json
import tarfile
import zipfile
import urllib.request
from pathlib import Path


def get_github_asset_url(owner: str, repo: str, release_tag: str, filename_prefix: str) -> str | None:
    """
    使用 GitHub Releases API 获取指定 tag 下、以 filename_prefix 开头的资产直链。

    返回 browser_download_url，未找到返回 None。
    """
    api = f"https://api.github.com/repos/{owner}/{repo}/releases/tags/{release_tag}"
    req = urllib.request.Request(api, headers={"Accept": "application/vnd.github+json"})
    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        for asset in data.get("assets", []):
            name = asset.get("name", "")
            if name.startswith(filename_prefix):
                return asset.get("browser_download_url")
        return None
    except Exception as e:
        print(f"[sherpa-onnx] get_github_asset_url error: {e}")
        return None


def _extract_archive(archive_path: str, dest_dir: str):
    Path(dest_dir).mkdir(parents=True, exist_ok=True)
    if archive_path.endswith(".zip"):
        with zipfile.ZipFile(archive_path, 'r') as zf:
            zf.extractall(dest_dir)
    elif archive_path.endswith(".tar.gz") or archive_path.endswith(".tgz"):
        with tarfile.open(archive_path, 'r:gz') as tf:
            tf.extractall(dest_dir)
    elif archive_path.endswith(".tar.bz2"):
        with tarfile.open(archive_path, 'r:bz2') as tf:
            tf.extractall(dest_dir)
    else:
        # 非压缩格式，忽略
        pass


def download_and_extract(url: str, output_dir: str) -> Path:
    """
    下载 URL 到 output_dir 并在是压缩包时解压，返回解压后根目录或文件路径。
    """
    Path(output_dir).mkdir(parents=True, exist_ok=True)
    file_name = url.split("/")[-1]
    file_path = os.path.join(output_dir, file_name)

    print(f"[sherpa-onnx] downloading: {url} -> {file_path}")
    urllib.request.urlretrieve(url, file_path)

    # 估算根目录名（常见打包规范）
    root_dir = file_name.replace(".tar.bz2", "").replace(".tar.gz", "").replace(".tgz", "").replace(".zip", "")
    extracted_dir_path = Path(output_dir) / root_dir

    try:
        _extract_archive(file_path, output_dir)
        if extracted_dir_path.exists():
            try:
                os.remove(file_path)
            except Exception:
                pass
            return extracted_dir_path
        return Path(file_path)
    except Exception as e:
        print(f"[sherpa-onnx] extract error: {e}")
        return Path(file_path)


def check_and_extract_local_file(url: str, output_dir: str) -> Path | None:
    """
    若本地已存在对应压缩包，尝试解压；若已存在解压目录则直接返回。
    """
    file_name = url.split("/")[-1]
    compressed_path = Path(output_dir) / file_name
    root_dir = file_name.replace(".tar.bz2", "").replace(".tar.gz", "").replace(".tgz", "").replace(".zip", "")
    extracted_dir = Path(output_dir) / root_dir

    if extracted_dir.exists():
        print(f"[sherpa-onnx] extracted directory exists: {extracted_dir}")
        return extracted_dir

    if compressed_path.exists():
        print(f"[sherpa-onnx] found local archive: {compressed_path}")
        try:
            _extract_archive(str(compressed_path), output_dir)
            try:
                os.remove(compressed_path)
            except Exception:
                pass
            return extracted_dir if extracted_dir.exists() else None
        except Exception as e:
            print(f"[sherpa-onnx] extract local archive failed: {e}")
            return None

    return None


