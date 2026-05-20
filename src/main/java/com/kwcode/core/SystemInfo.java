package com.kwcode.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.*;
import java.util.*;

/**
 * 系统信息收集器 - 收集运行环境信息供EnvProber和诊断使用
 * <p>
 * 纯确定性，零LLM调用。使用JMX和系统属性获取信息。
 * </p>
 * @origin Python: core.sysinfo.SystemInfo
 */
public class SystemInfo {

    private static final Logger log = LoggerFactory.getLogger(SystemInfo.class);

    /**
     * 收集完整系统信息
     * @origin Python: core.sysinfo.SystemInfo.collect() -> dict
     * @return 系统信息Map
     */
    public static Map<String, Object> collect() {
        Map<String, Object> info = new LinkedHashMap<>();

        info.put("os", collectOsInfo());
        info.put("jvm", collectJvmInfo());
        info.put("memory", collectMemoryInfo());
        info.put("runtime", collectRuntimeInfo());

        return info;
    }

    /**
     * 获取操作系统信息
     */
    public static Map<String, String> collectOsInfo() {
        Map<String, String> os = new LinkedHashMap<>();
        os.put("name", System.getProperty("os.name", "unknown"));
        os.put("version", System.getProperty("os.version", "unknown"));
        os.put("arch", System.getProperty("os.arch", "unknown"));
        os.put("user", System.getProperty("user.name", "unknown"));
        os.put("cwd", System.getProperty("user.dir", "unknown"));
        os.put("file_encoding", System.getProperty("file.encoding", "unknown"));
        return os;
    }

    /**
     * 获取JVM信息
     */
    public static Map<String, Object> collectJvmInfo() {
        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("version", System.getProperty("java.version", "unknown"));
        jvm.put("vendor", System.getProperty("java.vendor", "unknown"));
        jvm.put("home", System.getProperty("java.home", "unknown"));
        jvm.put("vm_name", System.getProperty("java.vm.name", "unknown"));
        jvm.put("vm_version", System.getProperty("java.vm.version", "unknown"));

        RuntimeMXBean runtimeMx = ManagementFactory.getRuntimeMXBean();
        jvm.put("pid", getPid());
        jvm.put("uptime_ms", runtimeMx.getUptime());
        jvm.put("input_args", runtimeMx.getInputArguments());

        return jvm;
    }

    /**
     * 获取内存信息
     */
    public static Map<String, Object> collectMemoryInfo() {
        Map<String, Object> mem = new LinkedHashMap<>();

        Runtime runtime = Runtime.getRuntime();
        mem.put("max_mb", runtime.maxMemory() / (1024 * 1024));
        mem.put("total_mb", runtime.totalMemory() / (1024 * 1024));
        mem.put("free_mb", runtime.freeMemory() / (1024 * 1024));
        mem.put("used_mb", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
        mem.put("available_processors", runtime.availableProcessors());

        MemoryMXBean memoryMx = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryMx.getHeapMemoryUsage();
        mem.put("heap_used_mb", heapUsage.getUsed() / (1024 * 1024));
        mem.put("heap_max_mb", heapUsage.getMax() / (1024 * 1024));

        return mem;
    }

    /**
     * 获取运行时信息
     */
    public static Map<String, Object> collectRuntimeInfo() {
        Map<String, Object> rt = new LinkedHashMap<>();

        ThreadMXBean threadMx = ManagementFactory.getThreadMXBean();
        rt.put("thread_count", threadMx.getThreadCount());
        rt.put("daemon_thread_count", threadMx.getDaemonThreadCount());
        rt.put("peak_thread_count", threadMx.getPeakThreadCount());

        ClassLoadingMXBean classMx = ManagementFactory.getClassLoadingMXBean();
        rt.put("loaded_class_count", classMx.getLoadedClassCount());
        rt.put("total_loaded_classes", classMx.getTotalLoadedClassCount());

        return rt;
    }

    /**
     * 获取当前进程PID
     */
    public static long getPid() {
        try {
            return ProcessHandle.current().pid();
        } catch (Exception e) {
            String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
            try {
                return Long.parseLong(runtimeName.split("@")[0]);
            } catch (Exception ex) {
                return -1;
            }
        }
    }

    /**
     * 检查内存是否充足
     * @param thresholdMb 阈值（MB）
     * @return true表示可用内存超过阈值
     */
    public static boolean isMemoryAvailable(int thresholdMb) {
        Runtime runtime = Runtime.getRuntime();
        long freeMb = (runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())) / (1024 * 1024);
        return freeMb > thresholdMb;
    }

    /**
     * 获取简要系统摘要
     */
    public static String getBriefSummary() {
        Runtime runtime = Runtime.getRuntime();
        return String.format("OS: %s %s | JVM: %s | Heap: %d/%dMB | CPUs: %d",
            System.getProperty("os.name"),
            System.getProperty("os.version"),
            System.getProperty("java.version"),
            (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024),
            runtime.maxMemory() / (1024 * 1024),
            runtime.availableProcessors()
        );
    }
}
