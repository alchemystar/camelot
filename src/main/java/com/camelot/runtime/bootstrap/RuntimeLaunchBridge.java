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

    private RuntimeLaunchBridge() {
    }

    public static void reset() {
        LAST_CONTEXT.set(null);
        LAST_POST_PROCESSOR.set(null);
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
}
