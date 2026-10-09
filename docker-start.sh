docker run -d --name toy-agent --restart unless-stopped \
  -p 8099:8099 \
  -e TZ=Asia/Shanghai \
  -e JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=75.0" \
  -v "$(pwd)/config.properties:/app/config.properties" \
  -v "$(pwd)/events:/app/events" \
  -v "$(pwd)/sandbox:/app/sandbox" \
  -v "$(pwd)/plugins:/app/plugins" \
  -v "$(pwd)/wiki:/app/wiki" \
  --log-driver json-file --log-opt max-size=10m --log-opt max-file=3 \
  registry.cn-hangzhou.aliyuncs.com/xfg-studio/toy-agent:1.0.4


docker run -d --name toy-agent --restart unless-stopped \
-p 8099:8099 \
-e TZ=Asia/Shanghai \
-e JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=75.0" \
-e LLM_BASE_URL=https://apihub.agnes-ai.com/v1 \
-e LLM_API_KEY=sk-46Sab5SEzuLZypINZf65k9WEqCMvVeqvRS6sl7rYEX5Cz8zd \
-e LLM_MODEL=agnes-3.0-flash \
-v "$(pwd)/config.properties:/app/config.properties" \
-v "$(pwd)/events:/app/events" \
-v "$(pwd)/sandbox:/app/sandbox" \
-v "$(pwd)/plugins:/app/plugins" \
-v "$(pwd)/wiki:/app/wiki" \
--log-driver json-file --log-opt max-size=10m --log-opt max-file=3 \
registry.cn-hangzhou.aliyuncs.com/xfg-studio/toy-agent:1.0.5
