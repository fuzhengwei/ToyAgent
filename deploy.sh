#!/bin/bash
# ── ToyAgent 一键部署（不用 docker compose，直接 docker run）──
# 用法：
#   1. 把本脚本放到一个空目录（作为部署目录）
#   2. （可选）准备 config.properties 填好 API Key，没有也能启动（回落 Mock 演示模型）
#   3. bash deploy.sh
#   4. 访问 http://<服务器IP>:8099
# 升级镜像：bash deploy.sh pull

set -e
IMAGE="registry.cn-hangzhou.aliyuncs.com/xfg-studio/toy-agent:latest"
PORT="${PORT:-8099}"

# ── 可选：只拉取新镜像（老容器继续跑）──
if [[ "$1" == "pull" ]]; then
  docker pull "$IMAGE"
  echo "镜像已拉取，重启容器请执行：docker rm -f toy-agent && bash deploy.sh"
  exit 0
fi

# ── 1. 准备运行时目录 ──
mkdir -p events sandbox plugins wiki

# ── 2. 没有 config.properties 就生成一份默认配置（不填 Key 走 Mock 模型）──
if [[ ! -f config.properties ]]; then
  cat > config.properties << 'EOF'
# ToyAgent 模型配置（也可启动后在页面左下角 ⚙ 配置，会写回本文件）
# api_host=
# api_key=
# model_name=
EOF
  echo "已生成默认 config.properties，可编辑填入 API Key（不填走 Mock 模型）"
fi

# ── 3. 清理旧容器 ──
docker rm -f toy-agent 2>/dev/null || true

# ── 4. 启动 ──
# 默认模型可通过环境变量直接配置（优先级：config.properties 非空值 > LLM_* > OPENAI_* > Mock）：
#   LLM_BASE_URL=http://... LLM_API_KEY=sk-xxx LLM_MODEL=模型名 bash deploy.sh
DOCKER_ENV=()
[[ -n "$LLM_BASE_URL" ]] && DOCKER_ENV+=(-e "LLM_BASE_URL=$LLM_BASE_URL")
[[ -n "$LLM_API_KEY"  ]] && DOCKER_ENV+=(-e "LLM_API_KEY=$LLM_API_KEY")
[[ -n "$LLM_MODEL"    ]] && DOCKER_ENV+=(-e "LLM_MODEL=$LLM_MODEL")

docker run -d \
  --name toy-agent \
  --restart unless-stopped \
  -p "${PORT}:8099" \
  -e TZ=Asia/Shanghai \
  -e JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=75.0" \
  "${DOCKER_ENV[@]}" \
  -v "$(pwd)/config.properties:/app/config.properties" \
  -v "$(pwd)/events:/app/events" \
  -v "$(pwd)/sandbox:/app/sandbox" \
  -v "$(pwd)/plugins:/app/plugins" \
  -v "$(pwd)/wiki:/app/wiki" \
  --log-driver json-file \
  --log-opt max-size=10m \
  --log-opt max-file=3 \
  "$IMAGE"

echo ""
echo "✅ ToyAgent 已启动"
echo "   访问地址：http://<服务器IP>:${PORT}"
echo "   查看日志：docker logs -f toy-agent"
echo "   停止服务：docker rm -f toy-agent"
