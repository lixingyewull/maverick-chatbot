FROM docker.m.daocloud.io/library/python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    espeak-ng libespeak-ng1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY docker/py3-tts/requirements.txt /app/requirements.txt
RUN pip config set global.index-url https://pypi.tuna.tsinghua.edu.cn/simple && \
    pip install -r /app/requirements.txt

COPY docker/py3-tts/server.py /app/server.py

EXPOSE 8082
CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8082"]


