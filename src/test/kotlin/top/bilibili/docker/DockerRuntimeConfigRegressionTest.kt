package top.bilibili.docker

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class DockerRuntimeConfigRegressionTest {
    // Docker 相关源码回归统一按 UTF-8 读取，避免宿主平台默认编码影响字符串断言。
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `docker runtime should use jdk image and enable native memory tracking summary by default`() {
        val dockerfile = read("Dockerfile")

        assertTrue(
            dockerfile.contains("FROM eclipse-temurin:17-jdk"),
            "Dockerfile should use a JDK runtime image so jcmd and NMT stay available in container",
        )
        assertTrue(
            dockerfile.contains("-XX:NativeMemoryTracking=summary"),
            "Dockerfile should enable NativeMemoryTracking summary mode by default",
        )
    }

    @Test
    fun `docker compose should enforce hard memory limit by default`() {
        val compose = read("docker-compose.yml")

        // 当前版本默认保留容器 512MB 内存硬限制，确保线上资源预算与本地运行模板一致。
        assertTrue(
            compose.contains("mem_limit: 512m"),
            "docker-compose should keep fixed mem_limit=512m hard cap by default",
        )
        // deploy 资源限制同样应声明 512MB，避免部署侧只配置了 mem_limit 导致行为不一致。
        assertTrue(
            compose.contains("memory: 512m"),
            "docker-compose should keep deploy memory=512m hard cap by default",
        )
        // 当前策略仅约束内存上限，不额外绑定 memswap 限制，避免在不同引擎上触发兼容问题。
        assertFalse(
            compose.contains("memswap_limit: 512m"),
            "docker-compose should not keep fixed memswap_limit=512m hard cap by default policy",
        )
        // /dev/shm 需要显式配置，避免渲染路径落回 Docker 默认 64MB 造成共享内存瓶颈。
        assertTrue(
            compose.contains("shm_size: 256m"),
            "docker-compose should keep explicit shm_size to avoid default 64MB shared memory limit",
        )
    }

    @Test
    fun `docker runtime should use unpooled netty allocator`() {
        val dockerfile = read("Dockerfile")

        // 低并发机器人场景优先减少池化管理开销，因此 Docker 运行时应显式切换到 unpooled。
        assertTrue(
            dockerfile.contains("-Dio.netty.allocator.type=unpooled"),
            "Dockerfile should force Netty allocator type to unpooled for low-concurrency runtime",
        )
        assertFalse(
            dockerfile.contains("-Dio.netty.allocator.type=pooled"),
            "Dockerfile should no longer keep pooled allocator once unpooled is selected",
        )
    }

    @Test
    fun `docker runtime and skiko initializer should both keep awt headless true`() {
        val dockerfile = read("Dockerfile")
        val skikoInitializer = read("src/main/kotlin/top/bilibili/SkikoInitializer.kt")

        // Docker 层和代码层同时约束 headless=true，避免启动时被后续 System.setProperty 覆盖。
        assertTrue(
            dockerfile.contains("-Djava.awt.headless=true"),
            "Dockerfile should set java.awt.headless to true",
        )
        assertFalse(
            dockerfile.contains("-Djava.awt.headless=false"),
            "Dockerfile should not keep java.awt.headless=false",
        )
        assertTrue(
            skikoInitializer.contains("System.setProperty(\"java.awt.headless\", \"true\")"),
            "SkikoInitializer should align with Docker headless=true strategy",
        )
        assertFalse(
            skikoInitializer.contains("System.setProperty(\"java.awt.headless\", \"false\")"),
            "SkikoInitializer should no longer force headless=false",
        )
    }

    @Test
    fun `docker runtime should avoid fixed 512mb jvm maxram budget`() {
        val dockerfile = read("Dockerfile")

        // Docker 默认不再强制 512MB 预算基线，保留 JVM 按运行环境自适应。
        assertFalse(
            dockerfile.contains("-XX:MaxRAM=512m"),
            "Dockerfile should remove fixed JVM MaxRAM=512m budget",
        )
        // Metaspace 仍维持上限，避免类元数据膨胀导致长期 RSS 抖动。
        assertTrue(
            dockerfile.contains("-XX:MaxMetaspaceSize=48m"),
            "Dockerfile should keep MaxMetaspaceSize guardrail for long-running stability",
        )
        // CodeCache 仍维持上限，避免低并发场景无效膨胀。
        assertTrue(
            dockerfile.contains("-XX:ReservedCodeCacheSize=32m"),
            "Dockerfile should keep ReservedCodeCacheSize guardrail for long-running stability",
        )
        // Skia 缓存上限继续保留，避免 native 缓存无界增长。
        assertTrue(
            dockerfile.contains("-Dskiko.resourceCache.maxBytes=50331648"),
            "Dockerfile should keep skiko.resourceCache.maxBytes guardrail for native memory stability",
        )
    }

    @Test
    fun `docker runtime should keep jemalloc decay aggressive without introducing legacy chunk tuning`() {
        val dockerfile = read("Dockerfile")

        // 约束 jemalloc 维持更积极的脏页回收，并保留当前线程与 arena 策略。
        assertTrue(
            dockerfile.contains("MALLOC_CONF=background_thread:true,dirty_decay_ms:2000,muzzy_decay_ms:2000,narenas:1,tcache:false"),
            "Dockerfile should keep jemalloc config with decay=2000 and narenas/background_thread strategy",
        )
        // lg_chunk 属于历史参数，当前镜像不应引入该调优项，避免运行时兼容风险。
        assertFalse(
            dockerfile.contains("lg_chunk"),
            "Dockerfile should not introduce legacy lg_chunk tuning in MALLOC_CONF",
        )
    }

    @Test
    fun `bare metal linux startup script should require jemalloc before launching java`() {
        val buildGradle = read("build.gradle.kts")

        // 裸机 Linux 启动脚本必须在 JVM 启动前完成 jemalloc 注入，避免回退到 glibc malloc 后继续放大 Anonymous/RSS 漂移。
        assertTrue(
            buildGradle.contains("JEMALLOC_LIB="),
            "distribution start.sh should track the selected jemalloc library path",
        )
        assertTrue(
            buildGradle.contains("ldconfig -p"),
            "distribution start.sh should discover jemalloc from the dynamic linker cache",
        )
        assertTrue(
            buildGradle.contains("libjemalloc.so.2"),
            "distribution start.sh should require libjemalloc.so.2 on Linux bare metal",
        )
        assertTrue(
            buildGradle.contains("exit 1"),
            "distribution start.sh should fail fast when jemalloc is unavailable",
        )
        assertTrue(
            buildGradle.contains("export LD_PRELOAD=\"\${'\$'}JEMALLOC_LIB\""),
            "distribution start.sh should preload jemalloc before launching Java",
        )
        assertTrue(
            buildGradle.contains("MALLOC_CONF=\"\${'\$'}{MALLOC_CONF:-background_thread:true,dirty_decay_ms:2000,muzzy_decay_ms:2000,narenas:1,tcache:false}\""),
            "distribution start.sh should default to the Docker-aligned jemalloc decay profile",
        )
    }

    @Test
    fun `bare metal windows startup script should document allocator preload boundary`() {
        val buildGradle = read("build.gradle.kts")

        // Windows 当前没有项目内 allocator preload 方案，脚本应明确边界而不是设置 Linux 专属 LD_PRELOAD。
        assertTrue(
            buildGradle.contains("Windows 不使用 Linux LD_PRELOAD allocator 注入"),
            "distribution start.bat should document why allocator preload is Linux-only",
        )
        assertFalse(
            buildGradle.contains("set LD_PRELOAD="),
            "distribution start.bat should not set Linux-only LD_PRELOAD on Windows",
        )
    }

    @Test
    fun `docker runtime should prebuild fontconfig cache to avoid first draw cold scan`() {
        val dockerfile = read("Dockerfile")

        // 通过镜像构建阶段预热 fontconfig 缓存，避免应用首帧触发全量字体扫描。
        assertTrue(
            dockerfile.contains("RUN fc-cache -fv"),
            "Dockerfile should run fc-cache during build so runtime does not pay first-render font scan cost",
        )
    }

    @Test
    fun `docker runtime should cap skiko resource cache size explicitly`() {
        val dockerfile = read("Dockerfile")

        // 显式限制 Skiko 资源缓存，避免容器内 native 缓存在低内存场景下无限增长。
        assertTrue(
            dockerfile.contains("-Dskiko.resourceCache.maxBytes=50331648"),
            "Dockerfile should define skiko.resourceCache.maxBytes=50331648 in JAVA_TOOL_OPTIONS",
        )
        val skikoInitializer = read("src/main/kotlin/top/bilibili/SkikoInitializer.kt")
        // 仅设置 JVM 属性不足以保证 Skia 缓存上限生效，必须由初始化代码显式调用 Graphics API 落盘到 native 层。
        assertTrue(
            skikoInitializer.contains("Graphics.resourceCacheTotalLimit"),
            "SkikoInitializer should apply resource cache total limit through Graphics.resourceCacheTotalLimit",
        )
        assertTrue(
            skikoInitializer.contains("Graphics.resourceCacheSingleAllocationByteLimit"),
            "SkikoInitializer should apply single allocation cache limit through Graphics.resourceCacheSingleAllocationByteLimit",
        )
    }

    @Test
    fun `docker runtime should preallocate code cache and cap tiered compilation level`() {
        val dockerfile = read("Dockerfile")

        // 预分配 CodeCache 到保留上限，避免运行期渐进扩容造成细粒度 committed 抖动。
        assertTrue(
            dockerfile.contains("-XX:InitialCodeCacheSize=32m"),
            "Dockerfile should preallocate InitialCodeCacheSize to 32m",
        )
        assertTrue(
            dockerfile.contains("-XX:ReservedCodeCacheSize=32m"),
            "Dockerfile should keep ReservedCodeCacheSize at 32m",
        )
        // 分层编译仅保留 C1，可显著降低运行期持续 C2 编译导致的 CodeCache 增量。
        assertTrue(
            dockerfile.contains("-XX:TieredStopAtLevel=1"),
            "Dockerfile should cap tiered compilation with TieredStopAtLevel=1",
        )
    }

    @Test
    fun `docker runtime should avoid aggressive jit and overly constrained g1 tuning for small heap`() {
        val dockerfile = read("Dockerfile")

        // 小堆低并发场景不应强行把 JIT 阈值压到 500，避免启动期无效编译和 CodeCache 浪费。
        assertFalse(
            dockerfile.contains("-XX:CompileThreshold=500"),
            "Dockerfile should not force CompileThreshold=500 in low-concurrency runtime profile",
        )
        assertFalse(
            dockerfile.contains("-XX:Tier4CompileThreshold=500"),
            "Dockerfile should not force Tier4CompileThreshold=500 in low-concurrency runtime profile",
        )
        // G1 region 与 IHOP 建议让 JVM 根据堆规模自适应，避免固定参数在小堆下引入反效果。
        assertFalse(
            dockerfile.contains("-XX:G1HeapRegionSize=4m"),
            "Dockerfile should not pin G1HeapRegionSize=4m for the 160m heap profile",
        )
        assertFalse(
            dockerfile.contains("-XX:InitiatingHeapOccupancyPercent=30"),
            "Dockerfile should not force InitiatingHeapOccupancyPercent=30 for small-heap runtime",
        )
        // Java 17 + G1 下 ParallelRefProcEnabled 默认为 true，显式配置属于噪声。
        assertFalse(
            dockerfile.contains("-XX:+ParallelRefProcEnabled"),
            "Dockerfile should not redundantly set ParallelRefProcEnabled for Java 17 G1 profile",
        )
    }

    @Test
    fun `docker runtime should tune jit and code cache flushing for long running noise reduction`() {
        val dockerfile = read("Dockerfile")

        // 长期运行场景应显式开启 CodeCache flushing，避免编译缓存长期累积后只增不回。
        assertTrue(
            dockerfile.contains("-XX:+UseCodeCacheFlushing"),
            "Dockerfile should explicitly enable UseCodeCacheFlushing for long-running workloads",
        )
        // 低并发 bot 无需默认并行编译线程数，限制 CICompilerCount 有助于降低编译突发。
        assertTrue(
            dockerfile.contains("-XX:CICompilerCount=2"),
            "Dockerfile should cap CICompilerCount to reduce low-concurrency compilation bursts",
        )
        // 适度提高编译阈值可以减少短生命周期热点过早进入 CodeCache。
        assertTrue(
            dockerfile.contains("-XX:CompileThreshold=10000"),
            "Dockerfile should raise CompileThreshold to reduce short-lived hot-path compilation noise",
        )
    }

    @Test
    fun `docker entrypoint should include rss watchdog for self protection under native memory growth`() {
        val entrypoint = read("docker-entrypoint.sh")

        // 启动脚本必须提供 RSS watchdog，用于 native 内存异常增长时主动退出并交由容器重启策略恢复。
        assertTrue(
            entrypoint.contains("memory_watchdog()"),
            "docker-entrypoint should define memory_watchdog function",
        )
        assertTrue(
            entrypoint.contains("MEMORY_THRESHOLD_MB"),
            "docker-entrypoint should allow RSS threshold override through MEMORY_THRESHOLD_MB",
        )
        assertTrue(
            entrypoint.contains("kill -TERM \"\$JAVA_PID\""),
            "docker-entrypoint should terminate Java process when watchdog threshold is exceeded",
        )
    }

    @Test
    fun `docker entrypoint cleanup should enforce bounded wait and kill hung java process`() {
        val entrypoint = read("docker-entrypoint.sh")

        // 清理流程必须有边界等待，避免 JVM 停机钩子卡住时无限阻塞。
        assertTrue(
            entrypoint.contains("for i in $(seq 1 8); do"),
            "docker-entrypoint cleanup should wait with bounded retries before force-kill",
        )
        assertTrue(
            entrypoint.contains("kill -KILL \"\$JAVA_PID\""),
            "docker-entrypoint cleanup should force-kill Java after timeout to avoid indefinite wait",
        )
    }

    @Test
    fun `docker entrypoint should not enforce fixed cgroup memory budget by default`() {
        val entrypoint = read("docker-entrypoint.sh")

        // 入口脚本不应默认拒绝无限制容器启动，由部署侧按需设置资源限制。
        assertFalse(
            entrypoint.contains("enforce_container_memory_budget()"),
            "docker-entrypoint should remove fixed cgroup budget enforcement by default",
        )
        assertFalse(
            entrypoint.contains("CONTAINER_MEMORY_LIMIT_MB"),
            "docker-entrypoint should remove fixed container memory budget env dependency by default",
        )
        // 不应保留绑定 512MB 预算的 watchdog 默认阈值。
        assertFalse(
            entrypoint.contains("MEMORY_THRESHOLD_MB:-460"),
            "docker-entrypoint should not keep 460MB watchdog default bound to 512MB budget",
        )
    }
}
