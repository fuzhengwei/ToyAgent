# ── ToyAgent Docker 镜像 ──
# 零依赖工程：mvn package 产出自包含 ToyAgent.jar，运行只需 JRE 17
# 构建阶段完成编译，运行阶段只带 JRE + jar + web 静态资源

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
# 国内网络环境使用阿里云 Maven 镜像源，避免直连 Maven Central 握手失败
RUN mkdir -p /root/.m2 && printf '%s\n' \
    '<?xml version="1.0" encoding="UTF-8"?>' \
    '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">' \
    '  <mirrors>' \
    '    <mirror>' \
    '      <id>aliyunmaven</id>' \
    '      <mirrorOf>central</mirrorOf>' \
    '      <name>Aliyun Maven Mirror</name>' \
    '      <url>https://maven.aliyun.com/repository/public</url>' \
    '    </mirror>' \
    '  </mirrors>' \
    '</settings>' > /root/.m2/settings.xml
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 运行期可写目录（events 沙箱/事件溯源、plugins 插件、wiki 知识库），建议通过 volume 挂载持久化
RUN mkdir -p /app/events /app/sandbox /app/plugins /app/wiki

COPY --from=build /src/target/ToyAgent.jar app.jar
COPY web ./web

EXPOSE 8099

# 健康检查：busybox wget 探测首页
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8099/ || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
