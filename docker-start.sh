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
