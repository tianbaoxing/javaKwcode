package com.kwcode.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * 统一事件总线（Event Sourcing模式）
 * <p>
 * append-only日志支持replay/调试，替代分散的on_status回调。
 * 支持通配符监听（event="*"），线程安全。
 * </p>
 * <p>
 * 理论来源：Event Sourcing（Martin Fowler）、CC 27个hook事件
 * </p>
 * @origin Python: core.event_bus.EventBus
 */
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    /** 全局单例（Orchestrator创建的那个） */
    private static volatile EventBus instance;

    /** 事件 → 处理器列表 */
    private final Map<String, List<BiConsumer<String, Map<String, Object>>>> handlers =
        new ConcurrentHashMap<>();

    /** 通配符监听器 */
    private final List<BiConsumer<String, Map<String, Object>>> wildcardHandlers =
        new CopyOnWriteArrayList<>();

    /** append-only事件日志 */
    private final List<Map<String, Object>> eventLog = Collections.synchronizedList(new ArrayList<>());

    public EventBus() {
        if (instance == null) {
            instance = this;
        }
    }

    /**
     * 返回全局单例
     * @origin Python: core.event_bus.EventBus.get_instance() -> EventBus|None
     * @return 全局EventBus实例，未创建返回null
     */
    public static EventBus getInstance() {
        return instance;
    }

    /**
     * 注册事件处理器
     * <p>
     * event="*"监听所有事件。
     * </p>
     * @origin Python: core.event_bus.EventBus.on(event: str, handler: Callable)
     * @param event 事件名称，"*"表示监听所有事件
     * @param handler 处理器函数，接收(事件名, 载荷)
     */
    public void on(String event, BiConsumer<String, Map<String, Object>> handler) {
        if ("*".equals(event)) {
            wildcardHandlers.add(handler);
        } else {
            handlers.computeIfAbsent(event, k -> new CopyOnWriteArrayList<>()).add(handler);
        }
    }

    /**
     * 移除事件处理器
     * @origin Python: core.event_bus.EventBus.off(event: str, handler: Callable)
     * @param event 事件名称
     * @param handler 要移除的处理器
     */
    public void off(String event, BiConsumer<String, Map<String, Object>> handler) {
        if ("*".equals(event)) {
            wildcardHandlers.remove(handler);
        } else {
            handlers.getOrDefault(event, List.of()).remove(handler);
        }
    }

    /**
     * 发射事件，通知所有监听器，同时记录到日志
     * @origin Python: core.event_bus.EventBus.emit(event: str, payload: dict|None)
     * @param event 事件名称
     * @param payload 事件载荷
     */
    public void emit(String event, Map<String, Object> payload) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("t", System.currentTimeMillis());
        entry.put("event", event);
        if (payload != null) entry.putAll(payload);
        eventLog.add(entry);

        List<BiConsumer<String, Map<String, Object>>> targetHandlers =
            handlers.getOrDefault(event, List.of());

        for (var h : targetHandlers) {
            try {
                h.accept(event, payload != null ? payload : Map.of());
            } catch (Exception e) {
                log.debug("EventBus handler error [{}]: {}", event, e.getMessage());
            }
        }
        for (var h : wildcardHandlers) {
            try {
                h.accept(event, payload != null ? payload : Map.of());
            } catch (Exception e) {
                log.debug("EventBus wildcard handler error [{}]: {}", event, e.getMessage());
            }
        }
    }

    /**
     * 返回完整事件日志副本
     * @origin Python: core.event_bus.EventBus.replay() -> list[dict]
     * @return 事件日志列表
     */
    public List<Map<String, Object>> replay() {
        return List.copyOf(eventLog);
    }

    /**
     * 清空事件日志（不影响已注册的handler）
     * @origin Python: core.event_bus.EventBus.clear_log()
     */
    public void clearLog() {
        eventLog.clear();
    }

    /**
     * 返回已注册handler总数
     * @origin Python: core.event_bus.EventBus.handler_count() -> int
     */
    public int handlerCount() {
        int total = wildcardHandlers.size();
        for (var list : handlers.values()) total += list.size();
        return total;
    }
}
