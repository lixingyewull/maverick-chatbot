FROM docker.m.daocloud.io/library/python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

RUN apt-get update && apt-get install -y --no-install-recommends \
    libsndfile1 git git-lfs libgomp1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY docker/cosyvoice-tts/requirements.txt /app/requirements.txt
RUN pip install -r /app/requirements.txt

COPY docker/cosyvoice-tts/server.py /app/server.py
COPY docker/cosyvoice-tts/utils.py /app/utils.py

EXPOSE 8082

ENV COSYVOICE_CODE_DIR=/opt/CosyVoice \
    COSYVOICE_MODEL_DIR=/models/CosyVoice2-0.5B \
    COSYVOICE_AUTO_DOWNLOAD=true \
    COSYVOICE_CODE_GIT_URL=https://github.com/FunAudioLLM/CosyVoice.git \
    COSYVOICE_MODEL_GIT_URL=https://www.modelscope.cn/iic/CosyVoice2-0.5B.git

CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8082"]


