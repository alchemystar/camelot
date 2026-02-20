package com.camelot.runtime.bootstrap;

import org.springframework.context.ConfigurableApplicationContext;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class RuntimeLaunchBridge {

    private static final AtomicReference<ConfigurableApplicationContext> LAST_CONTEXT =
            new AtomicReference<ConfigurableApplicationContext>();
    private static final AtomicReference<DaoMapperMockPostProcessor> LAST_POST_PROCESSOR =
            new AtomicReference<DaoMapperMockPostProcessor>();
    private static final AtomicReference<CallChainCollector> LAST_CALL_CHAIN_COLLECTOR =
            new AtomicReference<CallChainCollector>();

    private RuntimeLaunchBridge() {
    }

    public static void reset() {
        LAST_CONTEXT.set(null);
        LAST_POST_PROCESSOR.set(null);
        LAST_CALL_CHAIN_COLLECTOR.set(null);
        MethodPathRuntime.clear();
        MethodPathRuntime.disable();
    }

    public static void setContext(ConfigurableApplicationContext context) {
        LAST_CONTEXT.set(context);
    }

    public static void setPostProcessor(DaoMapperMockPostProcessor postProcessor) {
        LAST_POST_PROCESSOR.set(postProcessor);
    }

    public static ConfigurableApplicationContext getContext() {
        return LAST_CONTEXT.get();
    }

    public static Map<String, String> snapshotMockedBeanTypes() {
        DaoMapperMockPostProcessor postProcessor = LAST_POST_PROCESSOR.get();
        if (postProcessor == null) {
            return Collections.emptyMap();
        }
        return postProcessor.snapshotMockedBeanTypes();
    }

    public static void clearCallChain() {
        MethodPathRuntime.clear();
        CallChainCollector collector = LAST_CALL_CHAIN_COLLECTOR.get();
        if (collector != null) {
            collector.clear();
        }
    }

    public static String snapshotCallChainDot() {
        if (MethodPathRuntime.hasData()) {
            return MethodPathRuntime.toDot();
        }
        CallChainCollector collector = LAST_CALL_CHAIN_COLLECTOR.get();
        if (collector == null) {
            return "digraph CallChain {\n}\n";
        }
        return collector.toDot();
    }

    public static void enableCallChain() {
        MethodPathRuntime.enable();
    }

    public static void disableCallChain() {
        MethodPathRuntime.disable();
    }

    public static void setCallChainCollector(CallChainCollector collector) {
        LAST_CALL_CHAIN_COLLECTOR.set(collector);
    }
}
