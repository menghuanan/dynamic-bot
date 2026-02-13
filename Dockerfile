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
    fonts-noto-cjk \
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
# JVM 参数配置 - 固定内存策略
#
# 目标: 5-10分钟内内存稳定，无业务时无波动
#
# 实测数据参考:
#   - Heap used: ~30MB, 但 G1 需要预留空间
#   - Metaspace: ~25MB (稳定后)
#   - CodeCache: ~20MB (稳定后)
#   - Thread: ~3MB (35-40线程)
#   - Other (Skia): ~1MB
#
# 固定配置策略:
#   - 堆内存固定 192MB (Xms=Xmx)
#   - CodeCache 预分配 24MB
#   - Metaspace 初始 32MB
#   - 启动时立即占用 ~280MB，但无后续增长
# ============================================
ENV JAVA_TOOL_OPTIONS="\
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=100 \
    -XX:G1HeapRegionSize=4m \
    -XX:InitiatingHeapOccupancyPercent=30 \
    -XX:G1ReservePercent=15 \
    -XX:MaxDirectMemorySize=64m \
    -XX:MetaspaceSize=32m \
    -XX:MaxMetaspaceSize=48m \
    -XX:CompressedClassSpaceSize=16m \
    -XX:InitialCodeCacheSize=24m \
    -XX:ReservedCodeCacheSize=48m \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/app/logs/heapdump.hprof \
    -XX:+ExitOnOutOfMemoryError \
    -XX:+UseStringDeduplication \
    -XX:+ParallelRefProcEnabled \
    -XX:NativeMemoryTracking=summary \
    -XX:CompileThreshold=500 \
    -XX:Tier4CompileThreshold=500 \
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
ARG APP_VERSION=1.6
ENV DISPLAY=:99
ENV XVFB_SCREEN_SIZE=1920x1080x24
ENV XVFB_DISPLAY=:99

# 创建必要的目录
RUN mkdir -p /app/config /app/data /app/temp /app/logs

# 复制预编译的 JAR 文件
COPY build/libs/dynamic-bot-${APP_VERSION}.jar /app/dynamic-bot.jar

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
# 默认命令参数 - 固定堆内存
#
# 内存预算 (目标 ~280MB 启动, 限制 512MB):
#   - Heap: 192MB (固定, Xms=Xmx)
#   - Metaspace: 32-48MB (初始32MB)
#   - CodeCache: 24-48MB (初始24MB)
#   - DirectBuffer: 64MB
#   - CompressedClassSpace: 16MB
#   - Thread stacks: ~40MB (40线程 x 1MB)
#   - Other/Native: ~50MB (Skia + jemalloc 开销)
#
# 固定堆优势: 无 GC 导致的内存波动
# ============================================
CMD ["-Xms192m", "-Xmx192m"]
