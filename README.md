# Maverick Chatbot 运行指南

## 概述

Maverick Chatbot 是一个支持多角色扮演、语音对话的项目，后端使用 Spring Boot，前端使用 Vite，向量库使用 Chroma（Docker 运行）。

## 当前模型与服务选型

- ASR（语音转文字）：`sherpa-onnx` (本地服务)
- Embedding（向量化）：DashScope `text-embedding-v4`
- LLM（对话大模型）：DashScope `qwen-max`
- TTS（语音合成/声音复刻）：`Doubao-声音复刻`

## 环境依赖

- Java 21、Maven 3.9+
- Node.js 18+
- Docker 、Docker Compose

## 快速开始

### 1. 角色配置

角色配置文件：`src/main/resources/roles/roles.yaml`

角色头像存储位置：`frontend/public/avatars/角色头像.png`

角色文档存储位置：`src/main/resources/docs/<docsDir>/语料.txt`

```yaml
- id: #角色的id
  name: #角色名字
  avatar: #前端显示的角色头像，需要与frontend/public/avatars中文件名对应，示例： /avatars/*.png
  series: #角色所属系列
  voice: #火山引擎声音复刻大模型控制台的声音ID
  docsDir: #src/main/resources/docs下的属于该角色的文件夹名<docsDir>
  
  personaPrompt: |  #专属该角色的prompt，可以要求该角色的身份、性格、对话风格等
    身份: 你是霍格沃茨的学生，格兰芬多学院的找球手，击败了伏地魔的男孩。
    性格: 勇敢、忠诚、谦虚，但有时会有点冲动和固执。你非常看重朋友（尤其是罗恩和赫敏），对黑魔法深恶痛绝。
    说话风格:
      - 请务必保持回答简洁、口语化，就像在和朋友真实地聊天。
      - 避免长篇大论的解释，大多数回答应控制在1-3句话之内。
  examples: # few-shot，可以设置对话示例让角色输出更符合要求
    - user: 你最喜欢的魔法是什么？
      ai: 嗯…如果非要选一个的话，应该是“呼神护卫”吧。它不仅仅是一个强大的咒语，也提醒着我那些最快乐的记忆。
    - user: 斯内普教授是个什么样的人？
      ai: (叹气) 这很难说…他总是针对我，扣格兰芬多的分。但我后来才知道，他其实……是我认识的最勇敢的人之一。
```

### 2. RAG导入向量库

1. 启动chroma服务（向量库）

   ```
   docker compose up -d chroma
   ```

2. `roles.yaml`中配置角色文件夹名称`docsDir`，同时`src/main/resources/docs/<docsDir>` 中配置好该角色的语料文件

3. `src/main/resources/application.yml`配置Embedding模型，需要在百炼控制台获取API-Key

   ```
   langchain4j:
     community:
       dashscope:
         embedding-model:
           model-name: text-embedding-v4
           api-key: <Your API Key>
   ```

4. 运行向量导入程序，把docs中的的文件转换成向量存到向量库

   ```
   mvn -q -DskipTests exec:java -Dexec.mainClass=com.maverick.maverickchatbot.tools.RagIngestRunner
   ```

### 3. 声音复刻与合成

1. 开通火山引擎的声音复刻大模型服务，获取APP ID、Access Token，填入`src/main/resources/application.yml`

   ```
   tts:
     vendor: volc-demo
     volc:
       app-id: APP ID
       access-token: Access Token
   ```

2. 在声音复刻详情界面获取声音ID，填入`src/main/resources/roles/roles.yaml`

   ```
   - id: harry
     name: 哈利·波特
     voiceSamples:
       - spk_id: #火山引擎声音复刻大模型控制台的声音ID
         audio_path: #需要训练的音色音频文件 示例：src/main/resources/voice/*.wav   
   ```

3. 运行上传脚本

   ```
   python3 "src/main/java/com/maverick/maverickchatbot/tools/uploadAndStatus.py"
   ```

4. 等待控制台显示训练完成

### 4. 配置LLM

1. `src/main/resources/application.yml`配置模型，需要在百炼控制台获取API-Key

   ```
   langchain4j:
     community:
       dashscope:
         chat-model:
           model-name: qwen-max
           api-key: <Your API Key>
         embedding-model:
           model-name: text-embedding-v4
           api-key: <Your API Key>
   ```

### 5. 启动服务

1. 启动chroma服务

   ```
   docker compose up -d chroma
   ```

2. 启动ASR服务

   ```
   docker compose up -d sherpa-onnx-server
   ```

3. 启动前端

   ```
   cd frontend
   npm i
   npm run dev
   ```

4. 启动后端

   ```
   mvn -q spring-boot:run
   # 或
   ./mvnw -q spring-boot:run
   # 或打包后
   mvn -q -DskipTests package && java -jar target/maverick-chatbot-*.jar
   ```