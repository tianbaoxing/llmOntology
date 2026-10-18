package com.conca.ontology.agent.tool;

import io.agentscope.core.tool.Tool;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 系统资源工具：采集本服务 JVM 的运行时资源（对齐 jvmguard JMXTelemetryProvider 的采集面），
 * 并支持手动触发一次 Full GC。
 *
 * 采集项：
 * - 堆汇总（Runtime totalMemory/freeMemory/maxMemory）
 * - 分内存池 used/committed（Metaspace 扣除 Compressed Class Space 重复统计）
 * - 各垃圾回收器累计次数与耗时
 * - 进程/系统 CPU 使用率、系统负载
 * - 线程数（当前/峰值/守护）
 *
 * @author tianbaoxing
 * @date 2026-10-18
 */
public class SystemResourceTool {

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    @Tool(name = "get_system_resources",
            readOnly = true,
            concurrencySafe = true,
            description = "获取本服务 JVM 的当前系统资源快照：堆内存、各内存池 used/committed、"
                    + "垃圾回收累计次数与耗时、CPU 使用率、系统负载、线程数。"
                    + "当用户询问服务器内存、GC、CPU、线程等运行状况时必须调用本工具获取真实数据，"
                    + "禁止凭空推测。返回示例：\n"
                    + "系统资源快照（采集时间 15:04:05，已运行 3.2 小时）\n"
                    + "【堆】used 512.5 MB / committed 1.0 GB / max 4.0 GB\n"
                    + "【内存池 heap】G1 Eden Space: used 128.0 MB / committed 256.0 MB ...")
    public String getSystemResources() {
        Runtime runtime = Runtime.getRuntime();
        long total = runtime.totalMemory();
        long free = runtime.freeMemory();
        long max = runtime.maxMemory();
        long used = total - free;

        StringBuilder sb = new StringBuilder(1024);
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        sb.append("系统资源快照（采集时间 ").append(TIME_FORMATTER.format(Instant.now()))
                .append("，已运行 ").append(formatDuration(runtimeBean.getUptime())).append("）\n");

        sb.append("【堆】used ").append(toMb(used))
                .append(" / committed ").append(toMb(total))
                .append(" / max ").append(max == Long.MAX_VALUE ? "无限制" : toMb(max))
                .append('\n');

        appendMemoryPools(sb);
        appendGcCollectors(sb);
        appendCpu(sb);
        appendThreads(sb);
        return sb.toString();
    }

    @Tool(name = "run_gc",
            readOnly = false,
            concurrencySafe = false,
            description = "手动触发本服务 JVM 的一次 System.gc()（Full GC，会短暂暂停所有应用线程），"
                    + "并返回 GC 前后的堆使用对比。仅当用户明确要求手动触发垃圾回收时调用，"
                    + "禁止在常规资源查询流程中主动调用。返回示例：\n"
                    + "已触发 System.gc()：堆 used 512.5 MB → 301.2 MB，本次释放约 211.3 MB")
    public String runGc() {
        Runtime runtime = Runtime.getRuntime();
        long before = runtime.totalMemory() - runtime.freeMemory();
        System.gc();
        long after = runtime.totalMemory() - runtime.freeMemory();
        long freed = before - after;
        return "已触发 System.gc()：堆 used " + toMb(before) + " → " + toMb(after)
                + "，本次" + (freed >= 0 ? "释放约 " + toMb(freed) : "新增约 " + toMb(-freed));
    }

    /**
     * 分池列出 used/committed。Metaspace 在逻辑上包含 Compressed Class Space，
     * 为避免重复统计，Metaspace 池的数值会扣除 Compressed Class Space（对齐 JMXTelemetryProvider）。
     */
    private void appendMemoryPools(StringBuilder sb) {
        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
        MemoryPoolMXBean metaspace = null;
        MemoryPoolMXBean compressedClassSpace = null;
        List<String> heapLines = new ArrayList<>();
        List<String> nonHeapLines = new ArrayList<>();

        for (MemoryPoolMXBean pool : pools) {
            if (pool.getType() != MemoryType.HEAP && pool.getType() != MemoryType.NON_HEAP) {
                continue;
            }
            String name = pool.getName();
            if (name.equals("Metaspace")) {
                metaspace = pool;
            }
            if (name.equals("Compressed Class Space")) {
                compressedClassSpace = pool;
            }
            MemoryUsage usage = pool.getUsage();
            String line = "- " + name + ": used " + toMb(usage.getUsed())
                    + " / committed " + toMb(usage.getCommitted());
            if (pool.getType() == MemoryType.HEAP) {
                heapLines.add(line);
            } else {
                nonHeapLines.add(line);
            }
        }

        // Metaspace 扣除 Compressed Class Space，展示名改为 Metaspace (non-class)
        if (metaspace != null && compressedClassSpace != null) {
            MemoryUsage metaUsage = metaspace.getUsage();
            MemoryUsage ccsUsage = compressedClassSpace.getUsage();
            String line = "- Metaspace (non-class): used "
                    + toMb(Math.max(0, metaUsage.getUsed() - ccsUsage.getUsed()))
                    + " / committed "
                    + toMb(Math.max(0, metaUsage.getCommitted() - ccsUsage.getCommitted()));
            nonHeapLines.removeIf(l -> l.startsWith("- Metaspace:"));
            nonHeapLines.add(line);
        }

        sb.append("【内存池 heap】\n");
        heapLines.forEach(line -> sb.append(line).append('\n'));
        sb.append("【内存池 non-heap】\n");
        nonHeapLines.forEach(line -> sb.append(line).append('\n'));
    }

    private void appendGcCollectors(StringBuilder sb) {
        sb.append("【GC 累计】");
        List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
        for (int i = 0; i < collectors.size(); i++) {
            GarbageCollectorMXBean collector = collectors.get(i);
            long count = collector.getCollectionCount();
            long time = collector.getCollectionTime();
            if (count < 0) {
                continue;
            }
            if (i > 0) {
                sb.append("；");
            }
            sb.append(collector.getName()).append(": ")
                    .append(count).append(" 次 / ")
                    .append(time < 0 ? "不支持统计" : formatDuration(time));
        }
        sb.append('\n');
    }

    /**
     * CPU 采集对齐 JMXTelemetryProvider：getProcessCpuLoad/getSystemCpuLoad 是
     * com.sun.management.OperatingSystemMXBean 的扩展方法，HotSpot 上可靠可用，
     * 非 HotSpot 环境静默降级为"不可用"。
     */
    private void appendCpu(StringBuilder sb) {
        java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        sb.append("【CPU】");
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            double processCpu = sunOs.getProcessCpuLoad();
            double systemCpu = sunOs.getSystemCpuLoad();
            sb.append("进程 ").append(processCpu < 0 ? "不可用" : toPercent(processCpu))
                    .append(" / 系统 ").append(systemCpu < 0 ? "不可用" : toPercent(systemCpu));
        } else {
            sb.append("进程 不可用 / 系统 不可用");
        }
        double loadAverage = osBean.getSystemLoadAverage();
        sb.append("（系统负载 ").append(loadAverage < 0 ? "不可用" : String.format("%.2f", loadAverage))
                .append("）\n");
    }

    private void appendThreads(StringBuilder sb) {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        sb.append("【线程】当前 ").append(threadBean.getThreadCount())
                .append(" / 峰值 ").append(threadBean.getPeakThreadCount())
                .append(" / 守护 ").append(threadBean.getDaemonThreadCount())
                .append('\n');
    }

    private static String toMb(long bytes) {
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static String toPercent(double ratio) {
        return String.format("%.1f%%", ratio * 100);
    }

    private static String formatDuration(long millis) {
        Duration duration = Duration.ofMillis(millis);
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        if (hours > 0) {
            return hours + " 小时 " + minutes + " 分钟";
        }
        if (minutes > 0) {
            return minutes + " 分钟";
        }
        return duration.toSeconds() + " 秒";
    }
}
