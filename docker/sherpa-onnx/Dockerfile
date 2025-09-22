FROM docker.m.daocloud.io/library/python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

RUN apt-get update && apt-get install -y --no-install-recommends \
    libsndfile1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY docker/sherpa-onnx/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

COPY docker/sherpa-onnx/server.py /app/server.py
COPY docker/sherpa-onnx/utils.py /app/utils.py

EXPOSE 8081

ENV SHERPA_MODEL_DIR=/models \
    SHERPA_NUM_THREADS=2 \
    SHERPA_PROVIDER=cpu \
    SHERPA_DECODING=greedy_search

CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8081"]


