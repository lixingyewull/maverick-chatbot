.PHONY: help chroma-up chroma-down chroma-restart chroma-clean ingest backend build-jar run-jar frontend-install frontend dev all

DEFAULT_GOAL := help

help:
	@echo "可用命令："
	@echo "  make chroma-up        # 启动向量库 Chroma (docker compose)"
	@echo "  make chroma-down      # 停止并移除 Chroma 容器"
	@echo "  make chroma-restart   # 重启 Chroma 容器"
	@echo "  make chroma-clean     # 清空本地向量库数据 ./chroma-data/* (危险)"
	@echo "  make ingest           # 执行 RAG 入库 (根据 roles.yaml -> docsDir)"
	@echo "  make backend          # 启动后端 (Spring Boot)"
	@echo "  make build-jar        # 打包后端 JAR"
	@echo "  make run-jar          # 运行打包后的 JAR"
	@echo "  make frontend-install # 安装前端依赖"
	@echo "  make frontend         # 启动前端 (Vite dev)"
	@echo "  make dev              # 一键：chroma-up + ingest + backend (前端请另开终端运行 make frontend)"
	@echo "  make all              # 同 dev"

chroma-up:
	docker compose up -d chroma | cat

chroma-down:
	docker compose down chroma | cat || true

chroma-restart: chroma-down chroma-up

chroma-clean:
	rm -rf ./chroma-data/*

ingest:
	mvn -q -DskipTests exec:java -Dexec.mainClass=com.maverick.maverickchatbot.tools.RagIngestRunner | cat

backend:
	mvn -q spring-boot:run | cat

build-jar:
	mvn -q -DskipTests package | cat

run-jar: build-jar
	java -jar target/maverick-chatbot-*.jar | cat

frontend-install:
	cd frontend && npm i

frontend:
	cd frontend && npm run dev | cat

dev: chroma-up ingest backend

all: dev


