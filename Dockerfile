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
    # Skiko 图形库依赖（用于二维码生成和图片渲染）
    libgl1-mesa-glx \
    libx11-6 \
    libxrender1 \
    libxext6 \
    libfreetype6 \
    libxtst6 \
    libxi6 \
    # 虚拟显示环境（用于 headless 图形渲染）
    xvfb \
    # 清理缓存
    && fc-cache -fv \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# 复制 JAR 文件
COPY build/libs/dynamic-bot-1.1.jar /app/bot.jar

# 创建必要的目录
RUN mkdir -p /app/config /app/data /app/temp /app/logs

# 设置时区为上海
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 暴露端口（如果需要的话，目前主要是 WebSocket 客户端，不需要暴露端口）
# EXPOSE 8080

# 设置 JVM 参数
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:+UseContainerSupport"

# 设置虚拟显示环境
ENV DISPLAY=:99

# 启动命令（使用 xvfb-run 提供虚拟显示）
ENTRYPOINT ["sh", "-c", "Xvfb :99 -screen 0 1024x768x24 > /dev/null 2>&1 & java $JAVA_OPTS -jar /app/bot.jar"]

# 健康检查（检查进程是否存在）
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD pgrep -f "bot.jar" || exit 1
