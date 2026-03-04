# ============================================
# 运行时镜像
# 注意: 需要先在本地执行 gradle shadowJar 编译
# ============================================
FROM eclipse-temurin:17-jdk

# 设置工作目录
WORKDIR /app

# ============================================
# 安装系统依赖
# ============================================
RUN apt-get update && apt-get install -y --no-install-recommends \
    # jemalloc - 更好的内存分配器，减少碎片
    libjemalloc2 \
    # Xvfb 虚拟显示服务器
    xvfb \
    # 进程管理工具
    procps \
    # 字体支持 (Skia 绘图需要)
    fonts-dejavu-core \
    fonts-noto-color-emoji \
    # 基础 X11 库
    libx11-6 \
    libxext6 \
    libxrender1 \
    libxtst6 \
    libxi6 \
    # OpenGL 库 (即使使用软件渲染也需要)
    libgl1 \
    libgl1-mesa-dri \
    libglu1-mesa \
    libegl1 \
    libgles2 \
    # 字体和图形库
    libfreetype6 \
    libfontconfig1 \
    libharfbuzz0b \
    libpng16-16 \
    libjpeg-turbo8 \
    libwebp7 \
    zlib1g \
    # 清理缓存
    && rm -rf /var/lib/apt/lists/*

# ============================================
# jemalloc 配置 - 5秒后强制归还内存
# ============================================
ENV LD_PRELOAD=/usr/lib/x86_64-linux-gnu/libjemalloc.so.2
ENV MALLOC_CONF=background_thread:true,dirty_decay_ms:5000,muzzy_decay_ms:5000,narenas:1,tcache:false

# ============================================
# JVM 参数配置 - 低占用优先策略
#
# 目标: 降低常驻内存，同时保持 7x24 稳定
#
# 实测数据参考:
#   - Heap used: ~30MB, 但 G1 需要预留空间
#   - Metaspace: ~25MB (稳定后)
#   - CodeCache: ~20MB (稳定后)
#   - Thread: ~3MB (35-40线程)
#   - Other (Skia): ~1MB
#
# 当前策略:
#   - 堆内存默认 64m~160m
#   - 收紧 Direct/Metaspace/CodeCache 预留
#   - 保留绘图能力（Xvfb + Skia）
# ============================================
ENV JAVA_TOOL_OPTIONS="\
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=100 \
    -XX:G1HeapRegionSize=4m \
    -XX:InitiatingHeapOccupancyPercent=30 \
    -XX:G1ReservePercent=15 \
    -XX:MaxDirectMemorySize=24m \
    -XX:MetaspaceSize=16m \
    -XX:MaxMetaspaceSize=40m \
    -XX:CompressedClassSpaceSize=16m \
    -XX:InitialCodeCacheSize=8m \
    -XX:ReservedCodeCacheSize=32m \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/app/logs/heapdump.hprof \
    -XX:+ExitOnOutOfMemoryError \
    -XX:+UseStringDeduplication \
    -XX:+ParallelRefProcEnabled \
    -XX:CompileThreshold=500 \
    -XX:Tier4CompileThreshold=500 \
    -Xss256k \
    -Djdk.nio.maxCachedBufferSize=65536 \
    -Dio.netty.allocator.maxCachedBufferCapacity=65536 \
    -Dio.netty.allocator.cacheTrimIntervalMillis=5000 \
    -Dio.netty.allocator.useCacheForAllThreads=false \
    -Dio.netty.allocator.type=unpooled \
    -Djava.awt.headless=false \
    -Dskiko.renderApi=SOFTWARE \
    -Dskiko.hardwareAcceleration=false \
    -Dskiko.vsync.enabled=false \
    -Dsun.java2d.opengl=false \
    -Dsun.java2d.xrender=false \
    -Dsun.java2d.pmoffscreen=false"

# ============================================
# 应用配置
# ============================================
ARG APP_VERSION=auto
ENV DISPLAY=:99
ENV XVFB_SCREEN_SIZE=1280x720x24
ENV XVFB_DISPLAY=:99

# 创建必要的目录
RUN mkdir -p /app/config /app/data /app/temp /app/logs

# 复制预编译的 JAR 文件（支持自动选择最新版本）
COPY build/libs/dynamic-bot-*.jar /tmp/build-libs/
RUN set -eux; \
    if [ "$APP_VERSION" = "auto" ]; then \
        selected_jar="$(ls -1 /tmp/build-libs/dynamic-bot-*.jar | sort -V | tail -n 1)"; \
    else \
        selected_jar="/tmp/build-libs/dynamic-bot-${APP_VERSION}.jar"; \
    fi; \
    test -f "$selected_jar"; \
    cp "$selected_jar" /app/dynamic-bot.jar; \
    rm -rf /tmp/build-libs

# ============================================
# 健康检查 - 检查 Java 进程是否存活
# ============================================
HEALTHCHECK --interval=60s --timeout=10s --start-period=120s --retries=3 \
    CMD pgrep -f "dynamic-bot.jar" > /dev/null || exit 1

# ============================================
# 启动脚本
# ============================================
COPY docker-entrypoint.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]

# ============================================
# 默认命令参数 - 低基线堆内存
#
# 说明:
#   - 如需跨环境固定参数，建议在 docker-compose 中显式传入 command。
#   - shm_size、mem_limit、mem_reservation 属于运行时配置，应在 compose 设置。
# ============================================
CMD ["-Xms64m", "-Xmx160m"]
