# BiliBili Dynamic Bot Dockerfile
# 使用 Eclipse Temurin JRE 17 作为基础镜像
FROM eclipse-temurin:17-jre-jammy

# 设置工作目录
WORKDIR /app

# 安装必要的字体和依赖
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    # 字体支持（用于图片生成）
    fonts-noto-cjk \
    fonts-noto-color-emoji \
    fontconfig \
    # 清理缓存
    && fc-cache -fv \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# 复制 JAR 文件
COPY build/libs/dynamic-bot-1.0.jar /app/bot.jar

# 创建必要的目录
RUN mkdir -p /app/config /app/data /app/temp /app/logs

# 设置时区为上海
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 暴露端口（如果需要的话，目前主要是 WebSocket 客户端，不需要暴露端口）
# EXPOSE 8080

# 设置 JVM 参数
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:+UseContainerSupport"

# 启动命令
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/bot.jar"]

# 健康检查（检查进程是否存在）
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD pgrep -f "bot.jar" || exit 1
