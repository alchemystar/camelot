package com.camelot.runtime.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CamelotRuntimeContextInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Logger LOG = LoggerFactory.getLogger(CamelotRuntimeContextInitializer.class);

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        RuntimeLaunchBridge.reset();
        RuntimeLaunchBridge.setContext(context);

        Environment environment = context.getEnvironment();
        List<String> scanPackages = splitCsv(environment.getProperty("camelot.mock.scan-packages"));
        List<String> forceMockClassPrefixes = splitCsv(environment.getProperty("camelot.mock.force-class-prefixes"));
        Set<String> forceMockBeanNames = new LinkedHashSet<String>(
                splitCsv(environment.getProperty("camelot.mock.force-bean-names"))
        );
        Map<String, String> forceMockBeanTargetTypes =
                parseKeyValueMap(environment.getProperty("camelot.mock.force-bean-target-types"));
        List<String> mapperLocations = resolveMapperLocations(environment);
        Set<String> forceMissingTypeNames = new LinkedHashSet<String>(
                splitCsv(environment.getProperty("camelot.mock.force-missing-type-names"))
        );

        DaoMapperMockPostProcessor postProcessor = new DaoMapperMockPostProcessor(
                scanPackages,
                forceMockClassPrefixes,
                forceMockBeanNames,
                forceMockBeanTargetTypes,
                mapperLocations,
                forceMissingTypeNames
        );
        RuntimeLaunchBridge.setPostProcessor(postProcessor);
        context.addBeanFactoryPostProcessor(postProcessor);
        context.addApplicationListener(new ApplicationListener<ApplicationEvent>() {
            @Override
            public void onApplicationEvent(ApplicationEvent event) {
                onDestroyEvent(event);
            }
        });
    }

    private static List<String> resolveMapperLocations(Environment environment) {
        if (environment == null) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> locations = new LinkedHashSet<String>();
        addSplitValues(locations, environment.getProperty("mybatis.mapper-locations"));
        addSplitValues(locations, environment.getProperty("mybatis-plus.mapper-locations"));
        addSplitValues(locations, environment.getProperty("mybatis.mapperLocations"));
        return locations.isEmpty() ? Collections.<String>emptyList() : new ArrayList<String>(locations);
    }

    private static Map<String, String> parseKeyValueMap(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        LinkedHashMap<String, String> map = new LinkedHashMap<String, String>();
        String[] entries = text.split(";");
        for (String entry : entries) {
            if (entry == null) {
                continue;
            }
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int split = trimmed.indexOf(':');
            if (split <= 0 || split == trimmed.length() - 1) {
                continue;
            }
            String key = trimmed.substring(0, split).trim();
            String value = trimmed.substring(split + 1).trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                map.put(key, value);
            }
        }
        return map.isEmpty() ? Collections.<String, String>emptyMap() : map;
    }

    private static List<String> splitCsv(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> values = new LinkedHashSet<String>();
        addSplitValues(values, text);
        return values.isEmpty() ? Collections.<String>emptyList() : new ArrayList<String>(values);
    }

    private static void addSplitValues(Set<String> target, String text) {
        if (target == null || text == null) {
            return;
        }
        String[] commaSplit = text.split(",");
        for (String commaSegment : commaSplit) {
            if (commaSegment == null) {
                continue;
            }
            String[] semicolonSplit = commaSegment.split(";");
            for (String segment : semicolonSplit) {
                if (segment == null) {
                    continue;
                }
                String clean = segment.trim();
                if (!clean.isEmpty()) {
                    target.add(clean);
                }
            }
        }
    }

    private static void onDestroyEvent(Object event) {
        if (event == null) {
            return;
        }
        String eventType = event.getClass().getName();
        if (!eventType.endsWith("ContextClosedEvent") && !eventType.endsWith("ContextStoppedEvent")) {
            return;
        }
        Throwable trace = new Throwable("Spring context destroy trace");
        String threadName = Thread.currentThread().getName();
        String trigger = inferDestroyTrigger(trace.getStackTrace(), threadName);
        LOG.warn(
                "Observed Spring destroy event. eventType={} trigger={} thread={} stack:\n{}",
                eventType,
                trigger,
                threadName,
                stackTraceToString(trace)
        );
    }

    private static String inferDestroyTrigger(StackTraceElement[] stack, String threadName) {
        if (threadName != null && threadName.contains("spring-runtime-shutdown")) {
            return "runtime-shutdown-hook";
        }
        if (threadName != null
                && (threadName.contains("SpringContextShutdownHook")
                || threadName.contains("ShutdownHook"))) {
            return "jvm-shutdown-hook";
        }
        if (containsStack(stack, "com.camelot.runtime.bootstrap.SpringRuntimeBootstrapMain", "safeClose")) {
            return "bootstrap-safe-close";
        }
        if (containsStack(stack, "org.springframework.boot.SpringApplication", "handleRunFailure")) {
            return "spring-run-failure";
        }
        if (containsStack(stack, "org.springframework.context.support.AbstractApplicationContext", "close")) {
            return "application-context-close";
        }
        return "unknown";
    }

    private static boolean containsStack(StackTraceElement[] stack, String className, String methodName) {
        if (stack == null || className == null || methodName == null) {
            return false;
        }
        for (StackTraceElement element : stack) {
            if (element == null) {
                continue;
            }
            if (className.equals(element.getClassName()) && methodName.equals(element.getMethodName())) {
                return true;
            }
        }
        return false;
    }

    private static String stackTraceToString(Throwable throwable) {
        StringWriter writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);
        throwable.printStackTrace(printWriter);
        printWriter.flush();
        return writer.toString();
    }
}
