import os
import subprocess


def ensure_git_clone(url: str, target_dir: str):
    if os.path.exists(target_dir) and any(os.scandir(target_dir)):
        return
    os.makedirs(os.path.dirname(target_dir), exist_ok=True)
    # init git-lfs
    subprocess.call(["git", "lfs", "install"])  # best-effort
    code = subprocess.call(["git", "clone", url, target_dir])
    if code != 0:
        raise RuntimeError(f"git clone failed: {url}")
    # optional checkout specific ref (branch/tag/commit)
    ref = os.getenv("COSYVOICE_CODE_GIT_REF", "").strip()
    if ref:
        try:
            subprocess.check_call(["git", "checkout", ref], cwd=target_dir)
        except Exception:
            pass
    # pull lfs assets
    try:
        subprocess.check_call(["git", "lfs", "pull"], cwd=target_dir)
    except Exception:
        pass


