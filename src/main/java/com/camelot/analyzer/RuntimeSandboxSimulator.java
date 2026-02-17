package com.camelot.analyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.CannotLoadBeanClassException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.FileSystemResource;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isNative;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;

public class RuntimeSandboxSimulator {
    public static volatile RuntimeTraceCollector ACTIVE_COLLECTOR;
    public static volatile RuntimeTypeSnapshotCollector ACTIVE_TYPE_COLLECTOR;
    public static volatile RuntimeExecutionSnapshotCollector ACTIVE_EXECUTION_COLLECTOR;
    public static volatile SoftFailController ACTIVE_SOFT_FAIL_CONTROLLER;
    public static volatile Map<String, String> ACTIVE_UNIQUE_IMPL_BY_TYPE = Collections.emptyMap();
    public static volatile boolean ADVICE_DIAG_ENABLED = false;
    private static volatile Method SPRING_AOP_UTILS_GET_TARGET_CLASS_METHOD;
    private static volatile Method SPRING_AOP_PROXY_UTILS_ULTIMATE_TARGET_CLASS_METHOD;
    private static volatile boolean SPRING_PROXY_METHODS_INITIALIZED = false;
    private static final Map<String, String> PROXY_RUNTIME_TYPE_CACHE =
            new ConcurrentHashMap<String, String>();
    private static volatile boolean AGENT_INSTALLED = false;
    private static volatile InstrumentationStats LAST_INSTRUMENTATION_STATS;
    public static final AtomicInteger ADVICE_HITS = new AtomicInteger(0);
    public static final AtomicInteger NULL_COLLECTOR_HITS = new AtomicInteger(0);
    public static final AtomicInteger ADVICE_DIAG_LINES = new AtomicInteger(0);
    public static final AtomicInteger ADVICE_ENTER_GUARDED = new AtomicInteger(0);
    public static final AtomicInteger ADVICE_ENTER_ACCEPTED = new AtomicInteger(0);
    public static final int ADVICE_DIAG_MAX_LINES = Integer.getInteger(
            "camelot.runtime.debug.maxLines",
            5000
    ).intValue();
    public static final AtomicInteger ADVICE_DIAG_SUPPRESSED = new AtomicInteger(0);
    private static final ThreadLocal<Integer> ADVICE_REENTRY_DEPTH =
            new ThreadLocal<Integer>() {
                @Override
                protected Integer initialValue() {
                    return Integer.valueOf(0);
                }
            };

    public static void main(String[] args) throws Exception {
        CliOptions options = CliOptions.parse(args);
        Files.createDirectories(options.outputDir);

        ProjectClassIndex projectClassIndex = buildProjectClassIndex(options.classesRoots, options.debugRuntime);
        ProjectAwareClassLoader classLoader = buildClassLoader(options, projectClassIndex);
        installTracingAgent(options, projectClassIndex);

        RuntimeTraceCollector collector = new RuntimeTraceCollector(options.maxCalls);
        RuntimeTypeSnapshotCollector typeCollector = new RuntimeTypeSnapshotCollector(options.debugRuntime);
        RuntimeExecutionSnapshotCollector executionCollector = new RuntimeExecutionSnapshotCollector(
                options.maxRuntimeEvents,
                options.maxRuntimeObjects,
                options.debugRuntime
        );
        SoftFailController softFailController = SoftFailController.fromOptions(options);
        ACTIVE_COLLECTOR = collector;
        ACTIVE_TYPE_COLLECTOR = typeCollector;
        ACTIVE_EXECUTION_COLLECTOR = executionCollector;
        ACTIVE_SOFT_FAIL_CONTROLLER = softFailController;
        ACTIVE_UNIQUE_IMPL_BY_TYPE = Collections.emptyMap();
        PROXY_RUNTIME_TYPE_CACHE.clear();
        ADVICE_DIAG_ENABLED = options.debugRuntime;
        ADVICE_DIAG_LINES.set(0);
        ADVICE_DIAG_SUPPRESSED.set(0);
        ADVICE_ENTER_GUARDED.set(0);
        ADVICE_ENTER_ACCEPTED.set(0);
        if (options.debugRuntime) {
            System.out.println("[RUNTIME_DEBUG] COLLECTOR_INIT collector=" + System.identityHashCode(collector)
                    + " typeCollector=" + System.identityHashCode(typeCollector)
                    + " executionCollector=" + System.identityHashCode(executionCollector)
                    + " runtimeClassLoader=" + RuntimeSandboxSimulator.class.getClassLoader()
                    + " debugMaxLines=" + ADVICE_DIAG_MAX_LINES);
        }

        RuntimeTraceReport report = new RuntimeTraceReport();
        report.generatedAt = Instant.now().toString();
        report.entryClass = options.entryClass;
        report.entryMethod = options.entryMethod;
        report.tracePrefixes = new ArrayList<String>(options.tracePrefixes);
        report.classesRoots = toStringList(options.classesRoots);
        report.classpathEntries = toStringList(options.classpathEntries);
        report.arguments = new ArrayList<String>(options.arguments);
        report.discoveredProjectClassCount = projectClassIndex.classNames.size();
        report.useSpringContext = options.useSpringContext;
        report.softFailEnabled = options.softFail;
        report.softFailMaxSuppressions = options.softFailMaxSuppressions;
        report.softFailExceptionPrefixes = new ArrayList<String>(options.softFailExceptionPrefixes);
        report.softFailMethodTokens = new ArrayList<String>(options.softFailMethodTokens);

        long startedAt = System.currentTimeMillis();
        SandboxBeanFactory beanFactory = null;
        SpringContextRuntime springRuntime = null;
        BeanProvider beanProvider = null;
        try {
            if (options.debugRuntime) {
                printRuntimeStartDebug(options, classLoader, projectClassIndex);
            }
            beanFactory = new SandboxBeanFactory(classLoader, options, projectClassIndex);
            beanProvider = beanFactory;

            if (options.useSpringContext) {
                springRuntime = SpringContextRuntime.tryStart(options, classLoader);
                if (springRuntime != null) {
                    beanProvider = springRuntime;
                    report.springContextActive = true;
                    report.springBeanDefinitionCount = springRuntime.getBeanDefinitionCount();
                    report.springBeanDefinitions = springRuntime.snapshotBeanDefinitions(200);
                } else {
                    report.springContextActive = false;
                }
            }
            ACTIVE_UNIQUE_IMPL_BY_TYPE = buildUniqueConcreteTypeIndex(beanFactory, springRuntime, options.debugRuntime);

            Class<?> entryClass = Class.forName(options.entryClass, true, classLoader);
            Method entryMethod = resolveEntryMethod(entryClass, options.entryMethod, options.arguments.size());
            Object entryBean = resolveEntryBean(entryClass, beanProvider, beanFactory, options.debugRuntime);
            Object[] invokeArgs = buildInvokeArgs(entryMethod, options.arguments, beanProvider, options.debugRuntime);
            if (options.debugRuntime) {
                System.out.println("[RUNTIME_DEBUG] MainClassLoader=" + RuntimeSandboxSimulator.class.getClassLoader());
                System.out.println("[RUNTIME_DEBUG] EntryClassLoader=" + entryClass.getClassLoader());
                System.out.println("[RUNTIME_DEBUG] EntryBeanClassLoader=" + entryBean.getClass().getClassLoader());
                System.out.println("[RUNTIME_DEBUG] AdviceClassLoader=" + TraceAdvice.class.getClassLoader());
            }

            String rootMethodId = toMethodId(entryMethod);
            report.rootMethodId = rootMethodId;

            Object result = entryMethod.invoke(entryBean, invokeArgs);
            report.result = stringify(result);
        } catch (Throwable error) {
            report.error = rootErrorMessage(error);
            report.errorStack = stackTraceToString(error);
            Throwable missingClassCause = findMissingClassCause(error);
            if (missingClassCause != null) {
                System.out.println("[RUNTIME_DEBUG] Missing class detected: " + missingClassCause);
                printMissingClassDiagnostics(options, classLoader, beanFactory, missingClassCause);
            } else if (options.debugRuntime) {
                printErrorChain(error);
            }
        } finally {
            report.callCount = collector.getCallCount();
            report.edges = collector.snapshotEdges();
            report.durationMs = System.currentTimeMillis() - startedAt;
            report.loadedProjectClasses = classLoader.snapshotLoadedProjectClasses();
            report.skippedProjectClasses = classLoader.snapshotFailedProjectClasses();
            report.pipelineTypeInfos = typeCollector.snapshotPipelineTypeInfos(500);
            report.pipelineObservationCount = typeCollector.getObservationCount();
            report.runtimeEventCount = executionCollector.getEventCount();
            report.runtimeDroppedEvents = executionCollector.getDroppedEventCount();
            report.runtimeObjectCount = executionCollector.getObjectCount();
            report.runtimeEvents = executionCollector.snapshotEvents(options.maxRuntimeEvents);
            report.runtimeObjects = executionCollector.snapshotObjects(options.maxRuntimeObjects);
            report.softFailSuppressedCount = softFailController.getSuppressedCount();
            report.softFailSuppressedSamples = softFailController.snapshotSuppressedSamples(500);
            if (beanFactory != null) {
                report.sandboxBeanTypeInfos = beanFactory.snapshotBeanTypeInfos(500);
            }
            if (springRuntime != null) {
                report.springBeanTypeInfos = springRuntime.snapshotBeanTypeInfos(500);
            }
            ACTIVE_COLLECTOR = null;
            ACTIVE_TYPE_COLLECTOR = null;
            ACTIVE_EXECUTION_COLLECTOR = null;
            ACTIVE_SOFT_FAIL_CONTROLLER = null;
            ACTIVE_UNIQUE_IMPL_BY_TYPE = Collections.emptyMap();
            if (springRuntime != null) {
                springRuntime.close();
            }
            classLoader.close();
        }

        Path jsonPath = options.outputDir.resolve("runtime-trace.json");
        Path treePath = options.outputDir.resolve("runtime-trace-tree.txt");
        Path dotPath = options.outputDir.resolve("call-graph.dot");
        Path typesPath = options.outputDir.resolve("runtime-types.txt");
        Path executionPath = options.outputDir.resolve("runtime-execution.txt");
        Path callStackPath = options.outputDir.resolve("runtime-call-stack.txt");
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(jsonPath.toFile(), report);
        Files.write(treePath, buildTraceTree(report).getBytes(StandardCharsets.UTF_8));
        Files.write(dotPath, buildRuntimeCallGraphDot(report).getBytes(StandardCharsets.UTF_8));
        Files.write(typesPath, buildRuntimeTypeSummary(report).getBytes(StandardCharsets.UTF_8));
        Files.write(executionPath, buildRuntimeExecutionSummary(report).getBytes(StandardCharsets.UTF_8));
        Files.write(callStackPath, buildRuntimeCallStackSummary(report).getBytes(StandardCharsets.UTF_8));

        System.out.println("Runtime sandbox finished.");
        System.out.println("Trace JSON: " + jsonPath.toAbsolutePath());
        System.out.println("Trace tree: " + treePath.toAbsolutePath());
        System.out.println("DOT graph:  " + dotPath.toAbsolutePath());
        System.out.println("Type info:  " + typesPath.toAbsolutePath());
        System.out.println("Exec info:  " + executionPath.toAbsolutePath());
        System.out.println("Stack info: " + callStackPath.toAbsolutePath());
        System.out.println("Calls:      " + report.callCount);
        System.out.println("Edges:      " + report.edges.size());
        System.out.println("Pipelines:  " + report.pipelineTypeInfos.size());
        System.out.println("BeanTypes:  " + (report.sandboxBeanTypeInfos == null ? 0 : report.sandboxBeanTypeInfos.size())
                + "/" + (report.springBeanTypeInfos == null ? 0 : report.springBeanTypeInfos.size()));
        System.out.println("RuntimeEvt: " + report.runtimeEventCount
                + " (dropped=" + report.runtimeDroppedEvents + ")");
        System.out.println("RuntimeObj: " + report.runtimeObjectCount);
        System.out.println("SoftFail:   " + report.softFailSuppressedCount + "/" + report.softFailMaxSuppressions
                + " enabled=" + report.softFailEnabled);
        System.out.println("AdviceHits: " + ADVICE_HITS.get());
        System.out.println("NullCollect:" + NULL_COLLECTOR_HITS.get());
        System.out.println("GuardSkip:  " + ADVICE_ENTER_GUARDED.get());
        System.out.println("EnterOk:    " + ADVICE_ENTER_ACCEPTED.get());
        System.out.println("DbgSupp:    " + ADVICE_DIAG_SUPPRESSED.get());
        if (LAST_INSTRUMENTATION_STATS != null) {
            System.out.println("Discovered: " + LAST_INSTRUMENTATION_STATS.discovered.get());
            System.out.println("Transformed:" + LAST_INSTRUMENTATION_STATS.transformed.get());
            System.out.println("Ignored:    " + LAST_INSTRUMENTATION_STATS.ignored.get());
            System.out.println("Errors:     " + LAST_INSTRUMENTATION_STATS.errors.get());
        }
        if (report.error != null) {
            System.out.println("Error:      " + report.error);
            System.out.println("ErrorStack:");
            System.out.println(report.errorStack);
        }
        forceProcessExit(0, options.debugRuntime);
    }

    private static void forceProcessExit(int exitCode, boolean debugRuntime) {
        Thread killer = new Thread(() -> {
            try {
                Thread.sleep(1500L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (debugRuntime) {
                System.out.println("[RUNTIME_DEBUG] FORCE_HALT exitCode=" + exitCode);
            }
            Runtime.getRuntime().halt(exitCode);
        }, "runtime-sandbox-force-halt");
        killer.setDaemon(true);
        killer.start();
        System.exit(exitCode);
    }

    private static ProjectAwareClassLoader buildClassLoader(CliOptions options,
                                                            ProjectClassIndex projectClassIndex) throws IOException {
        List<URL> urls = new ArrayList<URL>();
        for (Path path : options.classesRoots) {
            urls.add(path.toUri().toURL());
        }
        for (Path path : options.classpathEntries) {
            urls.add(path.toUri().toURL());
        }
        return new ProjectAwareClassLoader(
                urls.toArray(new URL[0]),
                RuntimeSandboxSimulator.class.getClassLoader(),
                projectClassIndex,
                options.tracePrefixes,
                options.debugRuntime
        );
    }

    private static ProjectClassIndex buildProjectClassIndex(List<Path> classRoots, boolean debugRuntime) {
        ProjectClassIndex index = new ProjectClassIndex();
        int discovered = 0;
        for (Path root : classRoots) {
            if (root == null || !Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                List<Path> classFiles = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".class"))
                        .collect(Collectors.toList());
                for (Path classFile : classFiles) {
                    String fqcn = toClassName(root, classFile);
                    if (fqcn.contains("$")) {
                        continue;
                    }
                    discovered++;
                    index.add(fqcn, classFile);
                }
            } catch (IOException error) {
                if (debugRuntime) {
                    System.out.println("[RUNTIME_DEBUG] PROJECT_CLASS_INDEX_SCAN_FAIL root="
                            + root + " error=" + error.getClass().getName() + ": " + error.getMessage());
                }
            }
        }
        if (debugRuntime) {
            System.out.println("[RUNTIME_DEBUG] PROJECT_CLASS_INDEX_DISCOVER total="
                    + discovered + " unique=" + index.classNames.size());
        }
        return index;
    }

    private static void printRuntimeStartDebug(CliOptions options,
                                               URLClassLoader classLoader,
                                               ProjectClassIndex projectClassIndex) {
        System.out.println("[RUNTIME_DEBUG] projectDir=" + options.projectDir);
        System.out.println("[RUNTIME_DEBUG] entryClass=" + options.entryClass + " entryMethod=" + options.entryMethod);
        System.out.println("[RUNTIME_DEBUG] useSpringContext=" + options.useSpringContext);
        System.out.println("[RUNTIME_DEBUG] classesRoots=" + options.classesRoots.size() + " classpathEntries=" + options.classpathEntries.size());
        System.out.println("[RUNTIME_DEBUG] projectClassDiscovered=" + projectClassIndex.classNames.size());
        URL[] urls = classLoader.getURLs();
        for (int i = 0; i < urls.length; i++) {
            System.out.println("[RUNTIME_DEBUG] CLASSPATH[" + i + "] " + urls[i]);
        }
    }

    private static void printErrorChain(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 12) {
            System.out.println("[RUNTIME_DEBUG] ERROR_CHAIN[" + depth + "] "
                    + current.getClass().getName() + ": " + current.getMessage());
            current = current.getCause();
            depth++;
        }
    }

    private static Throwable findMissingClassCause(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 20) {
            if (current instanceof ClassNotFoundException || current instanceof NoClassDefFoundError) {
                return current;
            }
            current = current.getCause();
            depth++;
        }
        return null;
    }

    private static void printMissingClassDiagnostics(CliOptions options,
                                                     URLClassLoader classLoader,
                                                     SandboxBeanFactory beanFactory,
                                                     Throwable missingClassCause) {
        String missingClass = extractMissingClassName(missingClassCause);
        System.out.println("[RUNTIME_DEBUG] missingClass=" + missingClass);
        System.out.println("[RUNTIME_DEBUG] entryClass=" + options.entryClass + " entryMethod=" + options.entryMethod);
        System.out.println("[RUNTIME_DEBUG] classesRoots=" + options.classesRoots);
        System.out.println("[RUNTIME_DEBUG] classpathEntries=" + options.classpathEntries);
        URL[] urls = classLoader.getURLs();
        System.out.println("[RUNTIME_DEBUG] effectiveClassLoaderUrls=" + urls.length);
        for (int i = 0; i < urls.length; i++) {
            System.out.println("[RUNTIME_DEBUG] effectiveUrl[" + i + "] " + urls[i]);
        }

        if (beanFactory != null) {
            System.out.println("[RUNTIME_DEBUG] scannedClassCount=" + beanFactory.getScannedClassCount());
            List<String> suggestions = beanFactory.suggestClassNames(missingClass, 12);
            if (!suggestions.isEmpty()) {
                System.out.println("[RUNTIME_DEBUG] classSuggestions=");
                for (String suggestion : suggestions) {
                    System.out.println("[RUNTIME_DEBUG]   - " + suggestion);
                }
            }
        }
        System.out.println("[RUNTIME_DEBUG] hint: check target/classes exists, or pass --classes/--classpath explicitly.");
    }

    private static String extractMissingClassName(Throwable missingClassCause) {
        if (missingClassCause == null) {
            return "";
        }
        String raw = missingClassCause.getMessage();
        if (raw == null) {
            return missingClassCause.toString();
        }
        return raw.replace('/', '.');
    }

    private static synchronized void installTracingAgent(CliOptions options,
                                                         ProjectClassIndex projectClassIndex) {
        if (AGENT_INSTALLED) {
            return;
        }
        ByteBuddyAgent.install();
        final InstrumentationStats stats = new InstrumentationStats();
        final Set<String> projectClassNames = projectClassIndex == null
                ? Collections.<String>emptySet()
                : new LinkedHashSet<String>(projectClassIndex.classNames);
        ElementMatcher.Junction<TypeDescription> typeMatcher = new ElementMatcher.Junction.AbstractBase<TypeDescription>() {
            @Override
            public boolean matches(TypeDescription target) {
                if (target == null) {
                    return false;
                }
                return shouldTraceType(target.getName(), options.tracePrefixes, projectClassNames);
            }
        };

        AgentBuilder.Listener listener = new AgentBuilder.Listener() {
            @Override
            public void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
                if (shouldTraceType(typeName, options.tracePrefixes, projectClassNames)) {
                    stats.discovered.incrementAndGet();
                    if (options.debugRuntime) {
                        System.out.println("[RUNTIME_DEBUG] DISCOVER " + typeName + " loaded=" + loaded + " cl=" + classLoader);
                    }
                }
            }

            @Override
            public void onTransformation(TypeDescription typeDescription,
                                         ClassLoader classLoader,
                                         JavaModule module,
                                         boolean loaded,
                                         DynamicType dynamicType) {
                String typeName = typeDescription == null ? "" : typeDescription.getName();
                if (shouldTraceType(typeName, options.tracePrefixes, projectClassNames)) {
                    stats.transformed.incrementAndGet();
                    if (options.debugRuntime) {
                        System.out.println("[RUNTIME_DEBUG] TRANSFORM " + typeName + " loaded=" + loaded + " cl=" + classLoader);
                    }
                }
            }

            @Override
            public void onIgnored(TypeDescription typeDescription,
                                  ClassLoader classLoader,
                                  JavaModule module,
                                  boolean loaded) {
                String typeName = typeDescription == null ? "" : typeDescription.getName();
                if (shouldTraceType(typeName, options.tracePrefixes, projectClassNames)) {
                    stats.ignored.incrementAndGet();
                    if (options.debugRuntime) {
                        System.out.println("[RUNTIME_DEBUG] IGNORED " + typeName + " loaded=" + loaded + " cl=" + classLoader);
                    }
                }
            }

            @Override
            public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
                if (shouldTraceType(typeName, options.tracePrefixes, projectClassNames)) {
                    stats.errors.incrementAndGet();
                    if (options.debugRuntime) {
                        System.out.println("[RUNTIME_DEBUG] ERROR " + typeName + " loaded=" + loaded + " cl=" + classLoader + " error=" + throwable);
                    }
                }
            }

            @Override
            public void onComplete(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
                // no-op
            }
        };

        new AgentBuilder.Default()
                .with(listener)
                .ignore(nameStartsWith("java.")
                        .or(nameStartsWith("javax."))
                        .or(nameStartsWith("sun."))
                        .or(nameStartsWith("jdk."))
                        .or(nameStartsWith("net.bytebuddy."))
                        .or(nameStartsWith("com.fasterxml.jackson."))
                        .or(nameStartsWith("com.camelot.analyzer.RuntimeSandboxSimulator")))
                .type(typeMatcher)
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder,
                                                            TypeDescription typeDescription,
                                                            ClassLoader classLoader,
                                                            JavaModule module,
                                                            java.security.ProtectionDomain protectionDomain) {
                        return builder.visit(
                                Advice.to(TraceAdvice.class).on(
                                        isMethod()
                                                .and(not(isAbstract()))
                                                .and(not(isNative()))
                                                .and(not(isStatic()))
                                                .and(not(isSynthetic()))
                                                .and(not(named("toString")))
                                                .and(not(named("hashCode")))
                                                .and(not(named("equals")))
                                )
                        );
                    }
                })
                .installOnByteBuddyAgent();
        AGENT_INSTALLED = true;
        LAST_INSTRUMENTATION_STATS = stats;
    }

    private static boolean matchesAnyPrefix(String typeName, List<String> prefixes) {
        if (typeName == null || typeName.isEmpty()) {
            return false;
        }
        for (String prefix : prefixes) {
            if (typeName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldTraceType(String typeName,
                                           List<String> tracePrefixes,
                                           Set<String> projectClassNames) {
        if (typeName == null || typeName.isEmpty()) {
            return false;
        }
        if (projectClassNames != null && projectClassNames.contains(typeName)) {
            return true;
        }
        return matchesAnyPrefix(typeName, tracePrefixes);
    }

    public static void debugAdviceLine(String message) {
        if (!ADVICE_DIAG_ENABLED) {
            return;
        }
        int line = ADVICE_DIAG_LINES.incrementAndGet();
        if (line > ADVICE_DIAG_MAX_LINES) {
            if (ADVICE_DIAG_SUPPRESSED.getAndIncrement() == 0) {
                System.out.println("[RUNTIME_DEBUG] ADVICE_DEBUG_LOG_SUPPRESSED maxLines="
                        + ADVICE_DIAG_MAX_LINES + " (set -Dcamelot.runtime.debug.maxLines=...)");
            }
            return;
        }
        System.out.println("[RUNTIME_DEBUG] " + message);
    }

    public static boolean isAdviceReentryGuarded() {
        Integer depth = ADVICE_REENTRY_DEPTH.get();
        return depth != null && depth.intValue() > 0;
    }

    public static void enterAdviceReentryGuard() {
        Integer depth = ADVICE_REENTRY_DEPTH.get();
        int next = (depth == null ? 0 : depth.intValue()) + 1;
        ADVICE_REENTRY_DEPTH.set(Integer.valueOf(next));
    }

    public static void exitAdviceReentryGuard() {
        Integer depth = ADVICE_REENTRY_DEPTH.get();
        int current = depth == null ? 0 : depth.intValue();
        if (current <= 1) {
            ADVICE_REENTRY_DEPTH.remove();
            return;
        }
        ADVICE_REENTRY_DEPTH.set(Integer.valueOf(current - 1));
    }

    public static String normalizeAdviceMethodName(String methodName) {
        if (methodName == null) {
            return "unknown";
        }
        String trimmed = methodName.trim();
        if (trimmed.isEmpty()) {
            return "unknown";
        }
        return trimmed;
    }

    public static String resolveAdviceTypeName(String declaredTypeName, Object self) {
        String fallback = declaredTypeName == null ? "unknown" : declaredTypeName.trim();
        if (fallback.isEmpty()) {
            fallback = "unknown";
        }
        if (self == null) {
            return fallback;
        }
        Class<?> runtimeClass;
        try {
            runtimeClass = self.getClass();
        } catch (Throwable ignored) {
            return fallback;
        }
        if (runtimeClass == null) {
            return fallback;
        }
        String runtimeName = runtimeClass.getName();
        String springTargetType = resolveSpringAopTargetTypeName(self, runtimeClass, fallback);
        if (springTargetType != null) {
            return springTargetType;
        }
        if (Proxy.isProxyClass(runtimeClass)) {
            String mappedFromDeclared = mapDeclaredTypeToConcrete(fallback);
            if (mappedFromDeclared != null) {
                debugAdviceLine("ADVICE_TYPE_RESOLVE_PROXY declared=" + fallback + " mapped=" + mappedFromDeclared);
                return mappedFromDeclared;
            }
            String mappedFromInterfaces = mapProxyInterfacesToConcrete(runtimeClass);
            if (mappedFromInterfaces != null) {
                debugAdviceLine("ADVICE_TYPE_RESOLVE_PROXY ifaceMapped=" + mappedFromInterfaces + " proxy=" + runtimeClass.getName());
                return mappedFromInterfaces;
            }
            return fallback;
        }
        if (runtimeName == null || runtimeName.trim().isEmpty()) {
            return fallback;
        }
        if (runtimeName.contains("$$") || runtimeName.contains("$ByteBuddy$")) {
            Class<?> superClass = runtimeClass.getSuperclass();
            if (superClass != null && !Object.class.equals(superClass)) {
                String superName = superClass.getName();
                if (superName != null && !superName.trim().isEmpty()) {
                    return superName;
                }
            }
        }
        return runtimeName;
    }

    private static String resolveSpringAopTargetTypeName(Object self, Class<?> runtimeClass, String fallback) {
        if (self == null || runtimeClass == null) {
            return null;
        }
        String runtimeName = runtimeClass.getName();
        if (isBlank(runtimeName)) {
            return null;
        }
        if (!Proxy.isProxyClass(runtimeClass)
                && runtimeName.indexOf("$$") < 0
                && runtimeName.indexOf("$ByteBuddy$") < 0) {
            return null;
        }
        String cacheKey = runtimeName + "|" + fallback;
        String cached = PROXY_RUNTIME_TYPE_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Class<?> targetClass = invokeSpringTargetClassResolver(self);
        if (targetClass == null || Object.class.equals(targetClass)) {
            return null;
        }
        String targetName = targetClass.getName();
        if (isBlank(targetName)) {
            return null;
        }
        PROXY_RUNTIME_TYPE_CACHE.put(cacheKey, targetName);
        debugAdviceLine("ADVICE_TYPE_RESOLVE_SPRING_PROXY proxy=" + runtimeName + " target=" + targetName);
        return targetName;
    }

    private static Class<?> invokeSpringTargetClassResolver(Object proxy) {
        if (proxy == null) {
            return null;
        }
        ensureSpringProxyMethodsInitialized(proxy.getClass().getClassLoader());
        Method ultimate = SPRING_AOP_PROXY_UTILS_ULTIMATE_TARGET_CLASS_METHOD;
        if (ultimate != null) {
            try {
                Object resolved = ultimate.invoke(null, proxy);
                if (resolved instanceof Class) {
                    return (Class<?>) resolved;
                }
            } catch (Throwable ignored) {
                // fallback
            }
        }
        Method direct = SPRING_AOP_UTILS_GET_TARGET_CLASS_METHOD;
        if (direct != null) {
            try {
                Object resolved = direct.invoke(null, proxy);
                if (resolved instanceof Class) {
                    return (Class<?>) resolved;
                }
            } catch (Throwable ignored) {
                // fallback
            }
        }
        return null;
    }

    private static synchronized void ensureSpringProxyMethodsInitialized(ClassLoader preferredLoader) {
        if (SPRING_PROXY_METHODS_INITIALIZED) {
            return;
        }
        SPRING_AOP_UTILS_GET_TARGET_CLASS_METHOD = resolveStaticMethod(
                "org.springframework.aop.support.AopUtils",
                "getTargetClass",
                preferredLoader,
                Object.class
        );
        SPRING_AOP_PROXY_UTILS_ULTIMATE_TARGET_CLASS_METHOD = resolveStaticMethod(
                "org.springframework.aop.framework.AopProxyUtils",
                "ultimateTargetClass",
                preferredLoader,
                Object.class
        );
        SPRING_PROXY_METHODS_INITIALIZED = true;
    }

    private static Method resolveStaticMethod(String className,
                                              String methodName,
                                              ClassLoader preferredLoader,
                                              Class<?>... parameterTypes) {
        Class<?> targetClass = tryLoadClass(className, preferredLoader);
        if (targetClass == null) {
            targetClass = tryLoadClass(className, RuntimeSandboxSimulator.class.getClassLoader());
        }
        if (targetClass == null) {
            ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
            targetClass = tryLoadClass(className, contextLoader);
        }
        if (targetClass == null) {
            return null;
        }
        try {
            Method method = targetClass.getMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Class<?> tryLoadClass(String className, ClassLoader loader) {
        if (isBlank(className) || loader == null) {
            return null;
        }
        try {
            return Class.forName(className, false, loader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String mapProxyInterfacesToConcrete(Class<?> proxyClass) {
        if (proxyClass == null) {
            return null;
        }
        Class<?>[] interfaces = proxyClass.getInterfaces();
        if (interfaces == null || interfaces.length == 0) {
            return null;
        }
        for (Class<?> iface : interfaces) {
            if (iface == null) {
                continue;
            }
            String mapped = mapDeclaredTypeToConcrete(iface.getName());
            if (mapped != null) {
                return mapped;
            }
        }
        return null;
    }

    private static String mapDeclaredTypeToConcrete(String declaredTypeName) {
        if (declaredTypeName == null) {
            return null;
        }
        Map<String, String> index = ACTIVE_UNIQUE_IMPL_BY_TYPE;
        if (index == null || index.isEmpty()) {
            return null;
        }
        String mapped = index.get(declaredTypeName);
        if (mapped == null || mapped.trim().isEmpty()) {
            return null;
        }
        return mapped;
    }

    private static Map<String, String> buildUniqueConcreteTypeIndex(SandboxBeanFactory beanFactory,
                                                                    SpringContextRuntime springRuntime,
                                                                    boolean debugRuntime) {
        List<RuntimeBeanTypeInfo> merged = new ArrayList<RuntimeBeanTypeInfo>();
        if (beanFactory != null) {
            merged.addAll(beanFactory.snapshotBeanTypeInfos(20000));
        }
        if (springRuntime != null) {
            merged.addAll(springRuntime.snapshotBeanTypeInfos(20000));
        }
        if (merged.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, LinkedHashSet<String>> candidatesByType = new LinkedHashMap<String, LinkedHashSet<String>>();
        for (RuntimeBeanTypeInfo info : merged) {
            if (info == null || isBlank(info.concreteClass)) {
                continue;
            }
            String concrete = info.concreteClass.trim();
            registerUniqueTypeCandidate(candidatesByType, concrete, concrete);
            List<String> assignables = info.assignableTypes == null
                    ? Collections.<String>emptyList()
                    : info.assignableTypes;
            for (String assignable : assignables) {
                registerUniqueTypeCandidate(candidatesByType, assignable, concrete);
            }
        }
        Map<String, String> index = new LinkedHashMap<String, String>();
        int ambiguous = 0;
        for (Map.Entry<String, LinkedHashSet<String>> entry : candidatesByType.entrySet()) {
            String declared = entry.getKey();
            LinkedHashSet<String> candidates = entry.getValue();
            if (isBlank(declared) || candidates == null || candidates.isEmpty()) {
                continue;
            }
            if (candidates.size() == 1) {
                index.put(declared, candidates.iterator().next());
            } else {
                ambiguous++;
            }
        }
        if (debugRuntime) {
            System.out.println("[RUNTIME_DEBUG] TYPE_UNIQUE_IMPL_INDEX size=" + index.size()
                    + " ambiguous=" + ambiguous);
        }
        return index.isEmpty() ? Collections.<String, String>emptyMap() : index;
    }

    private static void registerUniqueTypeCandidate(Map<String, LinkedHashSet<String>> candidatesByType,
                                                    String declaredType,
                                                    String concreteType) {
        if (candidatesByType == null || isBlank(declaredType) || isBlank(concreteType)) {
            return;
        }
        String declared = declaredType.trim();
        String concrete = concreteType.trim();
        LinkedHashSet<String> candidates = candidatesByType.get(declared);
        if (candidates == null) {
            candidates = new LinkedHashSet<String>();
            candidatesByType.put(declared, candidates);
        }
        candidates.add(concrete);
    }

    private static Method resolveEntryMethod(Class<?> entryClass, String methodRaw, int argCount) {
        MethodSelector selector = MethodSelector.parse(methodRaw);
        Method fallback = null;
        for (Method method : entryClass.getDeclaredMethods()) {
            if (!selector.matches(method.getName(), method.getParameterCount())) {
                continue;
            }
            method.setAccessible(true);
            return method;
        }
        for (Method method : entryClass.getMethods()) {
            if (!selector.matches(method.getName(), method.getParameterCount())) {
                continue;
            }
            method.setAccessible(true);
            return method;
        }

        for (Method method : entryClass.getDeclaredMethods()) {
            if (method.getName().equals(selector.methodName) && method.getParameterCount() == argCount) {
                fallback = method;
                break;
            }
        }
        if (fallback != null) {
            fallback.setAccessible(true);
            return fallback;
        }
        throw new IllegalArgumentException("Entry method not found: " + methodRaw + " in class " + entryClass.getName());
    }

    private static Object resolveEntryBean(Class<?> entryClass,
                                           BeanProvider beanProvider,
                                           SandboxBeanFactory fallbackBeanFactory,
                                           boolean debugRuntime) {
        if (beanProvider != null) {
            try {
                Object bean = beanProvider.getBean(entryClass);
                if (bean != null) {
                    return bean;
                }
            } catch (Throwable error) {
                if (debugRuntime) {
                    System.out.println("[RUNTIME_DEBUG] ENTRY_BEAN_PROVIDER_FAIL provider="
                            + beanProvider.providerName() + " type=" + entryClass.getName()
                            + " error=" + error.getClass().getName() + ": " + error.getMessage());
                }
            }
        }
        if (fallbackBeanFactory != null) {
            if (debugRuntime) {
                System.out.println("[RUNTIME_DEBUG] ENTRY_BEAN_FALLBACK provider=SandboxBeanFactory type=" + entryClass.getName());
            }
            return fallbackBeanFactory.getBean(entryClass);
        }
        throw new IllegalStateException("Cannot resolve entry bean: " + entryClass.getName());
    }

    private static Object[] buildInvokeArgs(Method method,
                                            List<String> rawArgs,
                                            BeanProvider beanProvider,
                                            boolean debugRuntime) {
        Class<?>[] types = method.getParameterTypes();
        if (rawArgs.size() > types.length) {
            throw new IllegalArgumentException("Argument count mismatch. required<=" + types.length + " provided=" + rawArgs.size());
        }
        Object[] values = new Object[types.length];
        AutoArgumentGenerator generator = new AutoArgumentGenerator(beanProvider, debugRuntime);
        for (int i = 0; i < types.length; i++) {
            if (i < rawArgs.size()) {
                String raw = rawArgs.get(i);
                if (isAutoArgToken(raw)) {
                    values[i] = generator.generate(types[i]);
                } else {
                    values[i] = convertArg(types[i], rawArgs.get(i));
                }
            } else {
                values[i] = generator.generate(types[i]);
            }
            if (debugRuntime) {
                Object value = values[i];
                System.out.println("[RUNTIME_DEBUG] AUTO_ARG index="
                        + i + " type=" + types[i].getName()
                        + " valueType=" + (value == null ? "null" : value.getClass().getName())
                        + " value=" + stringify(value));
            }
        }
        return values;
    }

    private static boolean isAutoArgToken(String raw) {
        if (raw == null) {
            return false;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return "__auto__".equals(normalized) || "@auto".equals(normalized);
    }

    private static Object defaultArgumentValue(Class<?> targetType) {
        if (!targetType.isPrimitive()) {
            return null;
        }
        if (targetType == boolean.class) {
            return false;
        }
        if (targetType == char.class) {
            return '\0';
        }
        if (targetType == byte.class) {
            return (byte) 0;
        }
        if (targetType == short.class) {
            return (short) 0;
        }
        if (targetType == int.class) {
            return 0;
        }
        if (targetType == long.class) {
            return 0L;
        }
        if (targetType == float.class) {
            return 0.0f;
        }
        if (targetType == double.class) {
            return 0.0d;
        }
        return null;
    }

    private static Object convertArg(Class<?> targetType, String raw) {
        if (targetType == String.class) {
            return raw;
        }
        if (targetType == int.class || targetType == Integer.class) {
            return Integer.parseInt(raw);
        }
        if (targetType == long.class || targetType == Long.class) {
            return Long.parseLong(raw);
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return Boolean.parseBoolean(raw);
        }
        if (targetType == double.class || targetType == Double.class) {
            return Double.parseDouble(raw);
        }
        if (targetType == float.class || targetType == Float.class) {
            return Float.parseFloat(raw);
        }
        if (targetType == short.class || targetType == Short.class) {
            return Short.parseShort(raw);
        }
        if (targetType == byte.class || targetType == Byte.class) {
            return Byte.parseByte(raw);
        }
        if (targetType == char.class || targetType == Character.class) {
            return raw == null || raw.isEmpty() ? '\0' : raw.charAt(0);
        }
        throw new IllegalArgumentException("Unsupported arg type: " + targetType.getName());
    }

    private static class AutoArgumentGenerator {
        private static final int MAX_DEPTH = 6;
        private final BeanProvider beanProvider;
        private final boolean debugRuntime;
        private final Map<Class<?>, Object> cache = new LinkedHashMap<Class<?>, Object>();

        private AutoArgumentGenerator(BeanProvider beanProvider, boolean debugRuntime) {
            this.beanProvider = beanProvider;
            this.debugRuntime = debugRuntime;
        }

        private Object generate(Class<?> targetType) {
            return generate(targetType, 0, new LinkedHashSet<Class<?>>());
        }

        private Object generate(Class<?> targetType, int depth, Set<Class<?>> visiting) {
            if (targetType == null) {
                return null;
            }
            if (depth > MAX_DEPTH) {
                return defaultArgumentValue(targetType);
            }
            if (targetType.isPrimitive()) {
                return defaultArgumentValue(targetType);
            }
            if (targetType == String.class) {
                return "auto";
            }
            if (targetType == Integer.class) {
                return Integer.valueOf(0);
            }
            if (targetType == Long.class) {
                return Long.valueOf(0L);
            }
            if (targetType == Boolean.class) {
                return Boolean.FALSE;
            }
            if (targetType == Double.class) {
                return Double.valueOf(0.0d);
            }
            if (targetType == Float.class) {
                return Float.valueOf(0.0f);
            }
            if (targetType == Short.class) {
                return Short.valueOf((short) 0);
            }
            if (targetType == Byte.class) {
                return Byte.valueOf((byte) 0);
            }
            if (targetType == Character.class) {
                return Character.valueOf('\0');
            }
            if (targetType == Class.class) {
                return Object.class;
            }
            if (targetType == Optional.class) {
                return Optional.empty();
            }
            if (targetType.isEnum()) {
                Object[] constants = targetType.getEnumConstants();
                return constants == null || constants.length == 0 ? null : constants[0];
            }
            if (targetType.isArray()) {
                Class<?> componentType = targetType.getComponentType();
                Object array = Array.newInstance(componentType, 1);
                Object element = generate(componentType, depth + 1, visiting);
                if (element != null || !componentType.isPrimitive()) {
                    Array.set(array, 0, element);
                }
                return array;
            }
            if (Collection.class.isAssignableFrom(targetType)) {
                return instantiateCollection(targetType);
            }
            if (Map.class.isAssignableFrom(targetType)) {
                return instantiateMap(targetType);
            }
            if (targetType.isInterface() || Modifier.isAbstract(targetType.getModifiers())) {
                Object polymorphic = buildPolymorphicValue(targetType);
                if (polymorphic != null) {
                    return polymorphic;
                }
                return null;
            }

            Object cached = cache.get(targetType);
            if (cached != null) {
                return cached;
            }
            if (!visiting.add(targetType)) {
                return null;
            }
            try {
                Object instance = instantiateConcreteType(targetType, depth, visiting);
                if (instance != null) {
                    cache.put(targetType, instance);
                    populateFields(instance, targetType, depth, visiting);
                }
                return instance;
            } finally {
                visiting.remove(targetType);
            }
        }

        private Object instantiateConcreteType(Class<?> targetType, int depth, Set<Class<?>> visiting) {
            Constructor<?> noArg = null;
            for (Constructor<?> constructor : targetType.getDeclaredConstructors()) {
                if (constructor.getParameterCount() == 0) {
                    noArg = constructor;
                    break;
                }
            }
            if (noArg != null) {
                try {
                    noArg.setAccessible(true);
                    return noArg.newInstance();
                } catch (Throwable ignored) {
                    // fallback to other constructors
                }
            }

            List<Constructor<?>> constructors = Arrays.asList(targetType.getDeclaredConstructors());
            constructors = constructors.stream()
                    .sorted(Comparator.comparingInt(Constructor::getParameterCount))
                    .collect(Collectors.toList());
            for (Constructor<?> constructor : constructors) {
                try {
                    Object[] args = new Object[constructor.getParameterCount()];
                    Class<?>[] parameterTypes = constructor.getParameterTypes();
                    for (int i = 0; i < parameterTypes.length; i++) {
                        args[i] = generate(parameterTypes[i], depth + 1, visiting);
                        if (args[i] == null && parameterTypes[i].isPrimitive()) {
                            args[i] = defaultArgumentValue(parameterTypes[i]);
                        }
                    }
                    constructor.setAccessible(true);
                    return constructor.newInstance(args);
                } catch (Throwable ignored) {
                    // try next constructor
                }
            }
            if (beanProvider != null) {
                try {
                    return beanProvider.getBean(targetType);
                } catch (Throwable ignored) {
                    // give up
                }
            }
            return null;
        }

        private void populateFields(Object instance, Class<?> targetType, int depth, Set<Class<?>> visiting) {
            Class<?> current = targetType;
            while (current != null && current != Object.class) {
                Field[] fields = current.getDeclaredFields();
                for (Field field : fields) {
                    if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        Object currentValue = field.get(instance);
                        if (currentValue != null) {
                            continue;
                        }
                        Object value = generate(field.getType(), depth + 1, visiting);
                        if (value == null && field.getType().isPrimitive()) {
                            value = defaultArgumentValue(field.getType());
                        }
                        if (value != null || field.getType().isPrimitive()) {
                            field.set(instance, value);
                        }
                    } catch (Throwable ignored) {
                        if (debugRuntime) {
                            System.out.println("[RUNTIME_DEBUG] AUTO_ARG_FIELD_SKIP field="
                                    + field.getDeclaringClass().getName() + "." + field.getName());
                        }
                    }
                }
                current = current.getSuperclass();
            }
        }

        private Object buildPolymorphicValue(Class<?> targetType) {
            if (beanProvider != null) {
                try {
                    return beanProvider.getBean(targetType);
                } catch (Throwable ignored) {
                    // continue
                }
            }
            if (!targetType.isInterface()) {
                return null;
            }
            InvocationHandler handler = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    return defaultValue(method.getReturnType());
                }
            };
            try {
                ClassLoader proxyClassLoader = targetType.getClassLoader();
                if (proxyClassLoader == null) {
                    proxyClassLoader = RuntimeSandboxSimulator.class.getClassLoader();
                }
                return Proxy.newProxyInstance(proxyClassLoader, new Class[]{targetType}, handler);
            } catch (Throwable ignored) {
                return null;
            }
        }

        @SuppressWarnings("unchecked")
        private Object instantiateCollection(Class<?> collectionType) {
            if (!collectionType.isInterface() && !Modifier.isAbstract(collectionType.getModifiers())) {
                try {
                    Constructor<?> constructor = collectionType.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    return constructor.newInstance();
                } catch (Throwable ignored) {
                    // fallback
                }
            }
            if (List.class.isAssignableFrom(collectionType)) {
                return new ArrayList<Object>();
            }
            if (Set.class.isAssignableFrom(collectionType)) {
                return new LinkedHashSet<Object>();
            }
            if (Collection.class.isAssignableFrom(collectionType)) {
                return new ArrayList<Object>();
            }
            return null;
        }

        private Object instantiateMap(Class<?> mapType) {
            if (!mapType.isInterface() && !Modifier.isAbstract(mapType.getModifiers())) {
                try {
                    Constructor<?> constructor = mapType.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    return constructor.newInstance();
                } catch (Throwable ignored) {
                    // fallback
                }
            }
            return new LinkedHashMap<Object, Object>();
        }
    }

    private static String stringify(Object value) {
        if (value == null) {
            return "null";
        }
        return String.valueOf(value);
    }

    private static String rootErrorMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getClass().getName() + ": " + current.getMessage();
    }

    private static String stackTraceToString(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    private static List<String> toStringList(Collection<Path> paths) {
        List<String> list = new ArrayList<String>();
        for (Path path : paths) {
            list.add(path.toAbsolutePath().toString());
        }
        return list;
    }

    private static String toMethodId(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName();
    }

    private static String buildTraceTree(RuntimeTraceReport report) {
        if (report.edges == null || report.edges.isEmpty()) {
            if (report.rootMethodId != null && !report.rootMethodId.isEmpty() && report.callCount > 0) {
                return "Root: " + report.rootMethodId + "\n"
                        + "  (no nested traced edges captured; calls=" + report.callCount + ")\n";
            }
            return "No runtime edges captured.\n";
        }
        Map<String, List<RuntimeEdge>> outgoing = new LinkedHashMap<String, List<RuntimeEdge>>();
        for (RuntimeEdge edge : report.edges) {
            outgoing.computeIfAbsent(edge.from, k -> new ArrayList<RuntimeEdge>()).add(edge);
        }
        for (List<RuntimeEdge> edges : outgoing.values()) {
            edges.sort(Comparator.comparing((RuntimeEdge e) -> e.to));
        }

        String root = report.rootMethodId;
        if (root == null || root.isEmpty() || !outgoing.containsKey(root)) {
            root = report.edges.get(0).from;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Root: ").append(root).append("\n");
        Set<String> visiting = new LinkedHashSet<String>();
        appendTree(sb, root, outgoing, visiting, "  ", 0);
        return sb.toString();
    }

    private static String buildRuntimeCallGraphDot(RuntimeTraceReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph \"runtime_call_graph\" {\n");
        sb.append("  rankdir=LR;\n");
        sb.append("  node [shape=box, style=\"rounded\"];\n");

        List<RuntimeEdge> edges = report == null || report.edges == null
                ? Collections.<RuntimeEdge>emptyList()
                : new ArrayList<RuntimeEdge>(report.edges);
        edges.sort(Comparator.comparing((RuntimeEdge e) -> e.from).thenComparing((RuntimeEdge e) -> e.to));

        if (edges.isEmpty()) {
            String root = report == null || isBlank(report.rootMethodId) ? "NO_RUNTIME_EDGES" : report.rootMethodId;
            sb.append("  \"").append(escapeDotLabel(root)).append("\";\n");
            sb.append("}\n");
            return sb.toString();
        }

        for (RuntimeEdge edge : edges) {
            String from = edge == null ? "" : edge.from;
            String to = edge == null ? "" : edge.to;
            int count = edge == null ? 0 : edge.count;
            if (isBlank(from) || isBlank(to)) {
                continue;
            }
            sb.append("  \"").append(escapeDotLabel(from)).append("\" -> \"")
                    .append(escapeDotLabel(to)).append("\" [label=\"")
                    .append(count).append("\"];\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String escapeDotLabel(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String buildRuntimeTypeSummary(RuntimeTraceReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("Runtime Type Summary\n");
        sb.append("Entry: ").append(report.entryClass).append("#").append(report.entryMethod).append("\n");
        sb.append("Calls=").append(report.callCount).append(", Edges=")
                .append(report.edges == null ? 0 : report.edges.size()).append("\n\n");

        List<RuntimeBeanTypeInfo> sandboxBeans = report.sandboxBeanTypeInfos == null
                ? Collections.<RuntimeBeanTypeInfo>emptyList() : report.sandboxBeanTypeInfos;
        List<RuntimeBeanTypeInfo> springBeans = report.springBeanTypeInfos == null
                ? Collections.<RuntimeBeanTypeInfo>emptyList() : report.springBeanTypeInfos;
        sb.append("Sandbox Bean Types: ").append(sandboxBeans.size()).append("\n");
        for (RuntimeBeanTypeInfo bean : sandboxBeans) {
            sb.append("- ").append(bean.beanName).append(" -> ").append(bean.concreteClass)
                    .append(" [instantiated=").append(bean.instantiated).append("]\n");
        }
        sb.append("\nSpring Bean Types: ").append(springBeans.size()).append("\n");
        for (RuntimeBeanTypeInfo bean : springBeans) {
            sb.append("- ").append(bean.beanName).append(" -> ").append(bean.concreteClass)
                    .append(" [instantiated=").append(bean.instantiated).append("]\n");
        }

        List<RuntimePipelineTypeInfo> pipelineInfos = report.pipelineTypeInfos == null
                ? Collections.<RuntimePipelineTypeInfo>emptyList() : report.pipelineTypeInfos;
        sb.append("\nPipeline Types: ").append(pipelineInfos.size()).append("\n");
        for (RuntimePipelineTypeInfo pipeline : pipelineInfos) {
            sb.append("- ").append(pipeline.className).append("@").append(pipeline.instanceId)
                    .append(" methods=").append(pipeline.observedMethods)
                    .append(" stages=").append(pipeline.stageTypes).append("\n");
            if (!isBlank(pipeline.producedBy)) {
                sb.append("  producedBy=").append(pipeline.producedBy).append("\n");
            }
            if (!isBlank(pipeline.lastError)) {
                sb.append("  lastError=").append(pipeline.lastError).append("\n");
            }
        }
        return sb.toString();
    }

    private static String buildRuntimeExecutionSummary(RuntimeTraceReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("Runtime Execution Summary\n");
        sb.append("Entry: ").append(report.entryClass).append("#").append(report.entryMethod).append("\n");
        sb.append("Events=").append(report.runtimeEventCount)
                .append(", Dropped=").append(report.runtimeDroppedEvents)
                .append(", Objects=").append(report.runtimeObjectCount)
                .append(", SoftFailSuppressed=").append(report.softFailSuppressedCount).append("\n\n");

        List<RuntimeExecutionEvent> events = report.runtimeEvents == null
                ? Collections.<RuntimeExecutionEvent>emptyList() : report.runtimeEvents;
        sb.append("Events:\n");
        for (RuntimeExecutionEvent event : events) {
            sb.append("#").append(event.seq)
                    .append(" ").append(event.eventType)
                    .append(" ").append(event.methodId)
                    .append(" thread=").append(event.threadName)
                    .append(" depth=").append(event.stackDepth);
            if (!isBlank(event.parentMethodId)) {
                sb.append(" parent=").append(event.parentMethodId);
            }
            sb.append("\n");
            if (event.receiver != null && !isBlank(event.receiver.typeName)) {
                sb.append("  this=").append(event.receiver.typeName)
                        .append("@").append(event.receiver.identityId).append("\n");
            }
            if (event.receiverFields != null && !event.receiverFields.isEmpty()) {
                sb.append("  thisFields=").append(formatRuntimeFieldValues(event.receiverFields)).append("\n");
            }
            if (event.receiverFieldChanges != null && !event.receiverFieldChanges.isEmpty()) {
                sb.append("  thisFieldChanges=").append(formatRuntimeFieldChanges(event.receiverFieldChanges)).append("\n");
            }
            if (event.arguments != null && !event.arguments.isEmpty()) {
                sb.append("  args=");
                List<String> values = new ArrayList<String>();
                for (RuntimeValueInfo arg : event.arguments) {
                    values.add(arg == null ? "null" : arg.typeName + "@" + arg.identityId);
                }
                sb.append(values).append("\n");
            }
            if (event.returnValue != null && !isBlank(event.returnValue.typeName)) {
                sb.append("  return=").append(event.returnValue.typeName)
                        .append("@").append(event.returnValue.identityId).append("\n");
            }
            if (!isBlank(event.thrown)) {
                sb.append("  thrown=").append(event.thrown).append("\n");
            }
            if (event.softFailSuppressed) {
                sb.append("  softFailSuppressed=true\n");
            }
        }

        List<RuntimeObjectSnapshot> objects = report.runtimeObjects == null
                ? Collections.<RuntimeObjectSnapshot>emptyList() : report.runtimeObjects;
        sb.append("\nObjects:\n");
        for (RuntimeObjectSnapshot object : objects) {
            sb.append("- ").append(object.typeName).append("@").append(object.identityId).append("\n");
            if (object.interfaceTypes != null && !object.interfaceTypes.isEmpty()) {
                sb.append("  interfaces=").append(object.interfaceTypes).append("\n");
            }
            if (object.fieldRuntimeTypes != null && !object.fieldRuntimeTypes.isEmpty()) {
                sb.append("  fields=").append(object.fieldRuntimeTypes).append("\n");
            }
            if (object.fieldValueSummary != null && !object.fieldValueSummary.isEmpty()) {
                sb.append("  fieldValues=").append(object.fieldValueSummary).append("\n");
            }
            if (object.collectionElementTypes != null && !object.collectionElementTypes.isEmpty()) {
                sb.append("  elements=").append(object.collectionElementTypes).append("\n");
            }
            if (object.mapKeyTypes != null && !object.mapKeyTypes.isEmpty()) {
                sb.append("  mapKeys=").append(object.mapKeyTypes).append("\n");
            }
            if (object.mapValueTypes != null && !object.mapValueTypes.isEmpty()) {
                sb.append("  mapValues=").append(object.mapValueTypes).append("\n");
            }
        }
        return sb.toString();
    }

    private static String buildRuntimeCallStackSummary(RuntimeTraceReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("Runtime Call Stack\n");
        sb.append("Entry: ").append(report.entryClass).append("#").append(report.entryMethod).append("\n");
        sb.append("Events=").append(report.runtimeEventCount)
                .append(", SoftFailSuppressed=").append(report.softFailSuppressedCount).append("\n\n");
        List<RuntimeExecutionEvent> events = report.runtimeEvents == null
                ? Collections.<RuntimeExecutionEvent>emptyList()
                : report.runtimeEvents;
        for (RuntimeExecutionEvent event : events) {
            if (event == null) {
                continue;
            }
            int depth = Math.max(0, event.stackDepth - 1);
            for (int i = 0; i < depth; i++) {
                sb.append("  ");
            }
            String marker = "ENTER".equals(event.eventType) ? "->" : "<-";
            sb.append(marker).append(" ").append(event.methodId == null ? "" : event.methodId);
            if (!isBlank(event.thrown)) {
                sb.append(" [THROW ").append(event.thrown).append("]");
            }
            if (event.softFailSuppressed) {
                sb.append(" [SOFT_FAIL_SUPPRESSED]");
            }
            if (event.returnValue != null && !isBlank(event.returnValue.typeName)) {
                sb.append(" [RET ").append(event.returnValue.typeName).append("]");
            }
            sb.append("\n");
        }
        if (report.softFailSuppressedSamples != null && !report.softFailSuppressedSamples.isEmpty()) {
            sb.append("\nSuppressed Exceptions:\n");
            for (SoftFailSuppressedSample sample : report.softFailSuppressedSamples) {
                if (sample == null) {
                    continue;
                }
                sb.append("- #").append(sample.seq)
                        .append(" ").append(sample.methodId)
                        .append(" ").append(sample.throwableClass)
                        .append(": ").append(sample.message)
                        .append(" thread=").append(sample.threadName)
                        .append("\n");
            }
        }
        return sb.toString();
    }

    private static List<String> formatRuntimeFieldValues(List<RuntimeFieldValue> fields) {
        if (fields == null || fields.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        for (RuntimeFieldValue field : fields) {
            if (field == null) {
                continue;
            }
            String value = "null";
            if (field.value != null) {
                if (!isBlank(field.value.simpleValue)) {
                    value = field.value.typeName + ":" + field.value.simpleValue;
                } else {
                    value = field.value.typeName + "@" + field.value.identityId;
                }
            }
            out.add(field.ownerType + "." + field.fieldName + "=" + value);
        }
        return out;
    }

    private static List<String> formatRuntimeFieldChanges(List<RuntimeFieldChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        for (RuntimeFieldChange change : changes) {
            if (change == null) {
                continue;
            }
            out.add(change.ownerType + "." + change.fieldName + ": "
                    + change.before + " -> " + change.after);
        }
        return out;
    }

    private static List<String> collectAssignableTypeNames(Class<?> type) {
        if (type == null) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> ordered = new LinkedHashSet<String>();
        Deque<Class<?>> queue = new ArrayDeque<Class<?>>();
        queue.add(type);
        while (!queue.isEmpty()) {
            Class<?> current = queue.removeFirst();
            if (current == null) {
                continue;
            }
            if (!ordered.add(current.getName())) {
                continue;
            }
            Class<?> superClass = current.getSuperclass();
            if (superClass != null && superClass != Object.class) {
                queue.add(superClass);
            }
            Class<?>[] interfaces = current.getInterfaces();
            if (interfaces != null) {
                for (Class<?> iface : interfaces) {
                    if (iface != null) {
                        queue.add(iface);
                    }
                }
            }
        }
        return new ArrayList<String>(ordered);
    }

    private static void appendTree(StringBuilder sb,
                                   String current,
                                   Map<String, List<RuntimeEdge>> outgoing,
                                   Set<String> visiting,
                                   String indent,
                                   int depth) {
        if (depth > 30) {
            sb.append(indent).append("... depth limit reached\n");
            return;
        }
        List<RuntimeEdge> edges = outgoing.get(current);
        if (edges == null || edges.isEmpty()) {
            return;
        }
        visiting.add(current);
        for (RuntimeEdge edge : edges) {
            sb.append(indent).append("-> ").append(edge.to).append(" [count=").append(edge.count).append("]\n");
            if (!visiting.contains(edge.to)) {
                appendTree(sb, edge.to, outgoing, visiting, indent + "  ", depth + 1);
            } else {
                sb.append(indent).append("  (cycle)\n");
            }
        }
        visiting.remove(current);
    }

    private interface BeanProvider {
        Object getBean(Class<?> type);

        String providerName();
    }

    private static class SpringContextRuntime implements BeanProvider {
        private final AnnotationConfigApplicationContext context;
        private final boolean debugRuntime;

        private SpringContextRuntime(AnnotationConfigApplicationContext context, boolean debugRuntime) {
            this.context = context;
            this.debugRuntime = debugRuntime;
        }

        private static SpringContextRuntime tryStart(CliOptions options, ClassLoader classLoader) {
            AnnotationConfigApplicationContext context = null;
            try {
                context = new AnnotationConfigApplicationContext();
                context.setClassLoader(classLoader);
                context.getDefaultListableBeanFactory().setAllowBeanDefinitionOverriding(true);
                context.getDefaultListableBeanFactory().setAllowCircularReferences(true);

                if (options.tracePrefixes != null && !options.tracePrefixes.isEmpty()) {
                    String[] scanPackages = options.tracePrefixes.toArray(new String[0]);
                    context.scan(scanPackages);
                    if (options.debugRuntime) {
                        System.out.println("[RUNTIME_DEBUG] SPRING_CTX_SCAN packages=" + Arrays.toString(scanPackages));
                    }
                }

                List<Path> xmlFiles = discoverSpringXmlFiles(options.projectDir);
                if (!xmlFiles.isEmpty()) {
                    XmlBeanDefinitionReader reader = new XmlBeanDefinitionReader(context);
                    for (Path xmlFile : xmlFiles) {
                        if (!isLoadableSpringBeanXml(xmlFile, classLoader, options.debugRuntime)) {
                            continue;
                        }
                        try {
                            int loaded = reader.loadBeanDefinitions(new FileSystemResource(xmlFile.toFile()));
                            if (options.debugRuntime) {
                                System.out.println("[RUNTIME_DEBUG] SPRING_CTX_XML file="
                                        + xmlFile.toAbsolutePath() + " beanDefs=" + loaded);
                            }
                        } catch (Throwable xmlError) {
                            if (options.debugRuntime) {
                                System.out.println("[RUNTIME_DEBUG] SPRING_CTX_XML_SKIP file="
                                        + xmlFile.toAbsolutePath() + " error="
                                        + xmlError.getClass().getName() + ": " + xmlError.getMessage());
                            }
                        }
                    }
                }

                try {
                    context.refresh();
                } catch (CannotLoadBeanClassException missingClassError) {
                    if (options.debugRuntime) {
                        System.out.println("[RUNTIME_DEBUG] SPRING_CTX_REFRESH_SKIP bean="
                                + missingClassError.getBeanName() + " class="
                                + missingClassError.getBeanClassName() + " reason="
                                + missingClassError.getClass().getName() + ": " + missingClassError.getMessage());
                    }
                    throw missingClassError;
                }
                if (options.debugRuntime) {
                    System.out.println("[RUNTIME_DEBUG] SPRING_CTX_READY beanDefs=" + context.getBeanDefinitionCount());
                }
                return new SpringContextRuntime(context, options.debugRuntime);
            } catch (Throwable error) {
                if (options.debugRuntime) {
                    System.out.println("[RUNTIME_DEBUG] SPRING_CTX_START_FAIL error="
                            + error.getClass().getName() + ": " + error.getMessage());
                    printErrorChain(error);
                }
                if (context != null) {
                    try {
                        context.close();
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
                return null;
            }
        }

        private static List<Path> discoverSpringXmlFiles(Path projectDir) {
            if (projectDir == null || !Files.isDirectory(projectDir)) {
                return Collections.emptyList();
            }
            try (Stream<Path> stream = Files.walk(projectDir, 10)) {
                return stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.toString().toLowerCase(Locale.ROOT).endsWith(".xml"))
                        .filter(path -> !isIgnoredSpringXml(path))
                        .collect(Collectors.toList());
            } catch (IOException ignored) {
                return Collections.emptyList();
            }
        }

        private static boolean isIgnoredSpringXml(Path xmlFile) {
            if (xmlFile == null) {
                return true;
            }
            String normalized = xmlFile.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
            return normalized.contains("log4j")
                    || normalized.contains("logback")
                    || normalized.endsWith("/pom.xml")
                    || normalized.contains("/.m2repo/")
                    || normalized.contains("/.idea/")
                    || normalized.contains("/build/")
                    || normalized.contains("/target/");
        }

        private static boolean isLoadableSpringBeanXml(Path xmlFile,
                                                       ClassLoader classLoader,
                                                       boolean debugRuntime) {
            if (xmlFile == null) {
                return false;
            }
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setExpandEntityReferences(false);
                Document document = factory.newDocumentBuilder().parse(xmlFile.toFile());
                NodeList beanNodes = document.getElementsByTagName("bean");
                if (beanNodes == null || beanNodes.getLength() == 0) {
                    return false;
                }
                for (int i = 0; i < beanNodes.getLength(); i++) {
                    Element beanElement = (Element) beanNodes.item(i);
                    if (beanElement == null) {
                        continue;
                    }
                    String className = beanElement.getAttribute("class");
                    if (isBlank(className)) {
                        continue;
                    }
                    try {
                        Class.forName(className.trim(), false, classLoader);
                    } catch (Throwable missingClassError) {
                        if (debugRuntime) {
                            System.out.println("[RUNTIME_DEBUG] SPRING_CTX_XML_SKIP file="
                                    + xmlFile.toAbsolutePath() + " missingClass=" + className.trim()
                                    + " error=" + missingClassError.getClass().getName());
                        }
                        return false;
                    }
                }
                return true;
            } catch (Throwable parseError) {
                if (debugRuntime) {
                    System.out.println("[RUNTIME_DEBUG] SPRING_CTX_XML_IGNORE file="
                            + xmlFile.toAbsolutePath() + " error="
                            + parseError.getClass().getName() + ": " + parseError.getMessage());
                }
                return false;
            }
        }

        @Override
        public Object getBean(Class<?> type) {
            if (type == null || context == null) {
                return null;
            }
            try {
                return context.getBean(type);
            } catch (NoSuchBeanDefinitionException missing) {
                if (debugRuntime) {
                    System.out.println("[RUNTIME_DEBUG] SPRING_CTX_GET_MISS type=" + type.getName());
                }
                return null;
            } catch (BeansException error) {
                if (debugRuntime) {
                    System.out.println("[RUNTIME_DEBUG] SPRING_CTX_GET_FAIL type=" + type.getName()
                            + " error=" + error.getClass().getName() + ": " + error.getMessage());
                }
                return null;
            }
        }

        public int getBeanDefinitionCount() {
            if (context == null) {
                return 0;
            }
            return context.getBeanDefinitionCount();
        }

        public List<String> snapshotBeanDefinitions(int limit) {
            if (context == null) {
                return Collections.emptyList();
            }
            String[] names = context.getBeanDefinitionNames();
            List<String> values = new ArrayList<String>();
            if (names != null) {
                values.addAll(Arrays.asList(names));
            }
            Collections.sort(values);
            if (limit > 0 && values.size() > limit) {
                return new ArrayList<String>(values.subList(0, limit));
            }
            return values;
        }

        public List<RuntimeBeanTypeInfo> snapshotBeanTypeInfos(int limit) {
            if (context == null) {
                return Collections.emptyList();
            }
            List<RuntimeBeanTypeInfo> result = new ArrayList<RuntimeBeanTypeInfo>();
            String[] names = context.getBeanDefinitionNames();
            if (names == null || names.length == 0) {
                return result;
            }
            List<String> sorted = new ArrayList<String>(Arrays.asList(names));
            Collections.sort(sorted);
            for (String beanName : sorted) {
                if (limit > 0 && result.size() >= limit) {
                    break;
                }
                if (isBlank(beanName)) {
                    continue;
                }
                try {
                    Class<?> type = context.getType(beanName);
                    if (type == null) {
                        continue;
                    }
                    RuntimeBeanTypeInfo info = new RuntimeBeanTypeInfo();
                    info.source = "SpringContext";
                    info.beanName = beanName;
                    info.concreteClass = type.getName();
                    info.assignableTypes = collectAssignableTypeNames(type);
                    info.instantiated = context.getBeanFactory().containsSingleton(beanName);
                    result.add(info);
                } catch (Throwable error) {
                    if (debugRuntime) {
                        System.out.println("[RUNTIME_DEBUG] SPRING_CTX_BEAN_TYPE_SKIP bean=" + beanName
                                + " error=" + error.getClass().getName() + ": " + error.getMessage());
                    }
                }
            }
            return result;
        }

        public void close() {
            if (context != null) {
                context.close();
            }
        }

        @Override
        public String providerName() {
            return "SpringContextRuntime";
        }
    }

    public static class TraceAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static String onEnter(@Advice.Origin("#t") String typeName,
                                     @Advice.Origin("#m") String methodName,
                                     @Advice.This Object self,
                                     @Advice.AllArguments Object[] args) {
            ADVICE_HITS.incrementAndGet();
            String ownerTypeName = resolveAdviceTypeName(typeName, self);
            String ownerMethodName = normalizeAdviceMethodName(methodName);
            if (isAdviceReentryGuarded()) {
                ADVICE_ENTER_GUARDED.incrementAndGet();
                debugAdviceLine("ADVICE_ENTER_SKIP_GUARD type="
                        + ownerTypeName + " method=" + ownerMethodName);
                return null;
            }
            enterAdviceReentryGuard();
            try {
                RuntimeTraceCollector collector = ACTIVE_COLLECTOR;
                RuntimeTypeSnapshotCollector typeCollector = ACTIVE_TYPE_COLLECTOR;
                RuntimeExecutionSnapshotCollector executionCollector = ACTIVE_EXECUTION_COLLECTOR;
                String methodId = ownerTypeName + "#" + ownerMethodName;
                if (collector == null || methodId == null) {
                    NULL_COLLECTOR_HITS.incrementAndGet();
                    debugAdviceLine("ADVICE_ENTER_NO_COLLECTOR methodId=" + methodId
                            + " collector=" + (collector == null ? "null" : String.valueOf(System.identityHashCode(collector))));
                    if (typeCollector != null) {
                        try {
                            typeCollector.onMethodEnter(ownerTypeName, ownerMethodName, self, args);
                        } catch (Throwable error) {
                            debugAdviceLine("TYPE_COLLECTOR_ENTER_ERROR methodId=" + methodId
                                    + " error=" + error.getClass().getName() + ": " + error.getMessage());
                        }
                    }
                    if (executionCollector != null) {
                        try {
                            executionCollector.onMethodEnter(ownerTypeName, ownerMethodName, self, args);
                        } catch (Throwable error) {
                            debugAdviceLine("EXEC_COLLECTOR_ENTER_ERROR methodId=" + methodId
                                    + " error=" + error.getClass().getName() + ": " + error.getMessage());
                        }
                    }
                    return null;
                }
                int beforeCount = collector.getCallCount();
                try {
                    collector.onEnter(methodId);
                } catch (Throwable error) {
                    debugAdviceLine("TRACE_COLLECTOR_ENTER_ERROR methodId=" + methodId
                            + " error=" + error.getClass().getName() + ": " + error.getMessage());
                    return null;
                }
                int afterCount = collector.getCallCount();
                ADVICE_ENTER_ACCEPTED.incrementAndGet();
                debugAdviceLine("ADVICE_ENTER_OK methodId=" + methodId
                        + " collector=" + System.identityHashCode(collector)
                        + " callCount=" + beforeCount + "->" + afterCount
                        + " thread=" + Thread.currentThread().getName());
                if (typeCollector != null) {
                    try {
                        typeCollector.onMethodEnter(ownerTypeName, ownerMethodName, self, args);
                    } catch (Throwable error) {
                        debugAdviceLine("TYPE_COLLECTOR_ENTER_ERROR methodId=" + methodId
                                + " error=" + error.getClass().getName() + ": " + error.getMessage());
                    }
                }
                if (executionCollector != null) {
                    try {
                        executionCollector.onMethodEnter(ownerTypeName, ownerMethodName, self, args);
                    } catch (Throwable error) {
                        debugAdviceLine("EXEC_COLLECTOR_ENTER_ERROR methodId=" + methodId
                                + " error=" + error.getClass().getName() + ": " + error.getMessage());
                    }
                }
                return methodId;
            } finally {
                exitAdviceReentryGuard();
            }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Enter String methodId,
                                  @Advice.Origin("#t") String typeName,
                                  @Advice.Origin("#m") String methodName,
                                  @Advice.This Object self,
                                  @Advice.AllArguments Object[] args,
                                  @Advice.Thrown(readOnly = false) Throwable thrown) {
            if (methodId == null || isAdviceReentryGuarded()) {
                return;
            }
            String ownerTypeName = resolveAdviceTypeName(typeName, self);
            String ownerMethodName = normalizeAdviceMethodName(methodName);
            enterAdviceReentryGuard();
            try {
            RuntimeTraceCollector collector = ACTIVE_COLLECTOR;
            RuntimeTypeSnapshotCollector typeCollector = ACTIVE_TYPE_COLLECTOR;
            RuntimeExecutionSnapshotCollector executionCollector = ACTIVE_EXECUTION_COLLECTOR;
            SoftFailController softFailController = ACTIVE_SOFT_FAIL_CONTROLLER;
            Throwable observedThrown = thrown;
            boolean softFailSuppressed = false;
            if (softFailController != null
                    && softFailController.shouldSuppress(ownerTypeName, ownerMethodName, thrown)) {
                softFailController.recordSuppressed(methodId, thrown);
                softFailSuppressed = true;
                thrown = null;
            }
            if (collector == null || methodId == null) {
                if (typeCollector != null) {
                    try {
                        typeCollector.onMethodExit(ownerTypeName, ownerMethodName, self, args, null, observedThrown);
                    } catch (Throwable error) {
                        debugAdviceLine("TYPE_COLLECTOR_EXIT_ERROR methodId=" + methodId
                                + " error=" + error.getClass().getName() + ": " + error.getMessage());
                    }
                }
                if (executionCollector != null) {
                    try {
                        executionCollector.onMethodExit(ownerTypeName, ownerMethodName, self, args, null, observedThrown, softFailSuppressed);
                    } catch (Throwable error) {
                        debugAdviceLine("EXEC_COLLECTOR_EXIT_ERROR methodId=" + methodId
                                + " error=" + error.getClass().getName() + ": " + error.getMessage());
                    }
                }
                return;
            }
            try {
                collector.onExit(methodId);
            } catch (Throwable error) {
                debugAdviceLine("TRACE_COLLECTOR_EXIT_ERROR methodId=" + methodId
                        + " error=" + error.getClass().getName() + ": " + error.getMessage());
                return;
            }
            if (typeCollector != null) {
                try {
                    typeCollector.onMethodExit(ownerTypeName, ownerMethodName, self, args, null, observedThrown);
                } catch (Throwable error) {
                    debugAdviceLine("TYPE_COLLECTOR_EXIT_ERROR methodId=" + methodId
                            + " error=" + error.getClass().getName() + ": " + error.getMessage());
                }
            }
            if (executionCollector != null) {
                try {
                    executionCollector.onMethodExit(ownerTypeName, ownerMethodName, self, args, null, observedThrown, softFailSuppressed);
                } catch (Throwable error) {
                    debugAdviceLine("EXEC_COLLECTOR_EXIT_ERROR methodId=" + methodId
                            + " error=" + error.getClass().getName() + ": " + error.getMessage());
                }
            }
            } finally {
                exitAdviceReentryGuard();
            }
        }
    }

    public static class RuntimeTypeSnapshotCollector {
        private static final Set<String> PIPELINE_METHOD_HINTS = new LinkedHashSet<String>(
                Arrays.asList("pipeline", "builder", "chain", "stage", "handler", "flow")
        );
        private static final Set<String> PIPELINE_ASSEMBLY_METHODS = new LinkedHashSet<String>(
                Arrays.asList("add", "append", "register", "link", "then", "stage",
                        "addstage", "addhandler", "addstep", "push", "attach")
        );
        private static final Set<String> PIPELINE_BUILD_METHODS = new LinkedHashSet<String>(
                Arrays.asList("build", "create", "compose", "assemble")
        );
        private static final Set<String> STAGE_TYPE_HINTS = new LinkedHashSet<String>(
                Arrays.asList("stage", "handler", "step", "processor", "filter", "observer",
                        "visitor", "node", "task", "action")
        );
        private static final Set<String> STAGE_FIELD_HINTS = new LinkedHashSet<String>(
                Arrays.asList("stage", "handler", "step", "chain", "pipeline", "node", "filter")
        );

        private final boolean debugRuntime;
        private final Map<Integer, PipelineState> pipelineStates = new ConcurrentHashMap<Integer, PipelineState>();
        private final AtomicInteger observationCount = new AtomicInteger(0);

        public RuntimeTypeSnapshotCollector(boolean debugRuntime) {
            this.debugRuntime = debugRuntime;
        }

        public int getObservationCount() {
            return observationCount.get();
        }

        public void onMethodEnter(String typeName,
                                  String methodName,
                                  Object self,
                                  Object[] args) {
            if (!shouldObserve(typeName, methodName, self, args)) {
                return;
            }
            observationCount.incrementAndGet();
            String normalizedMethod = normalizeMethodName(methodName);
            String displayMethod = safeMethodName(methodName);
            if (self != null) {
                PipelineState selfState = getOrCreateState(self);
                selfState.observedMethods.add(displayMethod);
                selfState.evidence.add("enter:" + safeType(typeName) + "#" + displayMethod);
                if (isAssemblyMethod(normalizedMethod)) {
                    captureStageArgs(args, selfState, "arg");
                }
                captureContainerTypes(self, selfState, "self");
            }
            if (args != null) {
                for (Object arg : args) {
                    if (arg == null || !isPipelineLike(arg.getClass().getName())) {
                        continue;
                    }
                    PipelineState argState = getOrCreateState(arg);
                    argState.observedMethods.add("(as-arg)");
                    captureContainerTypes(arg, argState, "arg-pipeline");
                }
            }
        }

        public void onMethodExit(String typeName,
                                 String methodName,
                                 Object self,
                                 Object[] args,
                                 Object returned,
                                 Throwable thrown) {
            onMethodExit(typeName, methodName, self, args, returned, thrown, false);
        }

        public void onMethodExit(String typeName,
                                 String methodName,
                                 Object self,
                                 Object[] args,
                                 Object returned,
                                 Throwable thrown,
                                 boolean softFailSuppressed) {
            if (!shouldObserve(typeName, methodName, self, args) && returned == null && thrown == null) {
                return;
            }
            String normalizedMethod = normalizeMethodName(methodName);
            String displayMethod = safeMethodName(methodName);
            PipelineState selfState = null;
            if (self != null && isPipelineLike(self.getClass().getName())) {
                selfState = getOrCreateState(self);
                selfState.observedMethods.add(displayMethod);
                captureContainerTypes(self, selfState, "self-exit");
                if (thrown != null) {
                    selfState.lastError = thrown.getClass().getName() + ": " + thrown.getMessage();
                }
            }
            if (returned != null
                    && (isPipelineLike(returned.getClass().getName()) || isBuildMethod(normalizedMethod))) {
                PipelineState returnedState = getOrCreateState(returned);
                returnedState.observedMethods.add("return@" + displayMethod);
                returnedState.producedBy = safeType(typeName) + "#" + displayMethod;
                if (selfState != null && !selfState.stageTypes.isEmpty()) {
                    returnedState.stageTypes.addAll(selfState.stageTypes);
                    returnedState.evidence.add("copy-from:" + selfState.className + "@" + selfState.instanceId);
                }
                captureContainerTypes(returned, returnedState, "return");
            }
        }

        public List<RuntimePipelineTypeInfo> snapshotPipelineTypeInfos(int limit) {
            List<PipelineState> states = new ArrayList<PipelineState>(pipelineStates.values());
            states.sort(Comparator
                    .comparing((PipelineState s) -> s.className)
                    .thenComparingInt(s -> s.instanceId));
            List<RuntimePipelineTypeInfo> result = new ArrayList<RuntimePipelineTypeInfo>();
            for (PipelineState state : states) {
                if (state == null) {
                    continue;
                }
                if (state.stageTypes.isEmpty()
                        && state.observedMethods.isEmpty()
                        && isBlank(state.producedBy)
                        && isBlank(state.lastError)) {
                    continue;
                }
                if (limit > 0 && result.size() >= limit) {
                    break;
                }
                RuntimePipelineTypeInfo info = new RuntimePipelineTypeInfo();
                info.instanceId = state.instanceId;
                info.className = state.className;
                info.producedBy = state.producedBy;
                info.lastError = state.lastError;
                info.observedMethods = new ArrayList<String>(state.observedMethods);
                info.stageTypes = new ArrayList<String>(state.stageTypes);
                info.evidence = new ArrayList<String>(state.evidence);
                result.add(info);
            }
            return result;
        }

        private PipelineState getOrCreateState(Object value) {
            int instanceId = System.identityHashCode(value);
            PipelineState state = pipelineStates.get(instanceId);
            if (state != null) {
                return state;
            }
            PipelineState created = new PipelineState(instanceId, value.getClass().getName());
            PipelineState existing = pipelineStates.putIfAbsent(instanceId, created);
            return existing == null ? created : existing;
        }

        private boolean shouldObserve(String typeName, String methodName, Object self, Object[] args) {
            String normalizedMethod = normalizeMethodName(methodName);
            if (isPipelineMethodHint(normalizedMethod) || isAssemblyMethod(normalizedMethod) || isBuildMethod(normalizedMethod)) {
                return true;
            }
            if (self != null && isPipelineLike(self.getClass().getName())) {
                return true;
            }
            if (isPipelineLike(typeName)) {
                return true;
            }
            if (args != null) {
                for (Object arg : args) {
                    if (arg != null && isPipelineLike(arg.getClass().getName())) {
                        return true;
                    }
                }
            }
            return false;
        }

        private void captureStageArgs(Object[] args, PipelineState state, String source) {
            if (args == null || state == null) {
                return;
            }
            for (Object arg : args) {
                if (arg == null) {
                    continue;
                }
                String typeName = arg.getClass().getName();
                if (isSimpleType(arg.getClass())) {
                    continue;
                }
                if (isLikelyStageType(typeName) || !isPipelineLike(typeName)) {
                    state.stageTypes.add(typeName);
                    state.evidence.add(source + ":" + typeName);
                }
            }
        }

        private void captureContainerTypes(Object target, PipelineState state, String source) {
            if (target == null || state == null) {
                return;
            }
            Class<?> current = target.getClass();
            int depth = 0;
            while (current != null && current != Object.class && depth < 8) {
                Field[] fields = current.getDeclaredFields();
                for (Field field : fields) {
                    if (field == null || Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object fieldValue;
                    try {
                        fieldValue = field.get(target);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (fieldValue == null) {
                        continue;
                    }
                    String fieldName = field.getName() == null ? "" : field.getName().toLowerCase(Locale.ROOT);
                    if (fieldValue instanceof Collection) {
                        captureFromCollection((Collection<?>) fieldValue, state, source + ":field=" + fieldName);
                        continue;
                    }
                    if (fieldValue instanceof Map) {
                        captureFromCollection(((Map<?, ?>) fieldValue).values(), state, source + ":field=" + fieldName);
                        continue;
                    }
                    if (fieldValue.getClass().isArray()) {
                        int len = Array.getLength(fieldValue);
                        int upper = Math.min(len, 40);
                        for (int i = 0; i < upper; i++) {
                            Object element = Array.get(fieldValue, i);
                            captureStageValue(element, state, source + ":field=" + fieldName);
                        }
                        continue;
                    }
                    if (containsAny(fieldName, STAGE_FIELD_HINTS)) {
                        captureStageValue(fieldValue, state, source + ":field=" + fieldName);
                    }
                }
                current = current.getSuperclass();
                depth++;
            }
        }

        private void captureFromCollection(Collection<?> collection, PipelineState state, String source) {
            if (collection == null || state == null) {
                return;
            }
            int index = 0;
            for (Object item : collection) {
                if (index++ > 40) {
                    break;
                }
                captureStageValue(item, state, source);
            }
        }

        private void captureStageValue(Object value, PipelineState state, String source) {
            if (value == null || state == null) {
                return;
            }
            Class<?> valueType = value.getClass();
            if (isSimpleType(valueType)) {
                return;
            }
            String typeName = valueType.getName();
            if (isLikelyStageType(typeName) || !isPipelineLike(typeName)) {
                state.stageTypes.add(typeName);
                state.evidence.add(source + ":" + typeName);
            }
        }

        private boolean isPipelineMethodHint(String methodName) {
            if (isBlank(methodName)) {
                return false;
            }
            String normalized = methodName.toLowerCase(Locale.ROOT);
            return containsAny(normalized, PIPELINE_METHOD_HINTS);
        }

        private boolean isAssemblyMethod(String methodName) {
            if (isBlank(methodName)) {
                return false;
            }
            return PIPELINE_ASSEMBLY_METHODS.contains(methodName.toLowerCase(Locale.ROOT));
        }

        private boolean isBuildMethod(String methodName) {
            if (isBlank(methodName)) {
                return false;
            }
            return PIPELINE_BUILD_METHODS.contains(methodName.toLowerCase(Locale.ROOT));
        }

        private boolean isPipelineLike(String typeName) {
            if (isBlank(typeName)) {
                return false;
            }
            String normalized = typeName.toLowerCase(Locale.ROOT);
            return containsAny(normalized, PIPELINE_METHOD_HINTS);
        }

        private boolean isLikelyStageType(String typeName) {
            if (isBlank(typeName)) {
                return false;
            }
            String normalized = typeName.toLowerCase(Locale.ROOT);
            return containsAny(normalized, STAGE_TYPE_HINTS);
        }

        private boolean containsAny(String text, Set<String> tokens) {
            if (isBlank(text) || tokens == null || tokens.isEmpty()) {
                return false;
            }
            for (String token : tokens) {
                if (text.contains(token)) {
                    return true;
                }
            }
            return false;
        }

        private String safeType(String value) {
            return value == null ? "unknown" : value;
        }

        private String normalizeMethodName(String methodName) {
            if (methodName == null) {
                return "unknown";
            }
            return methodName.trim().toLowerCase(Locale.ROOT);
        }

        private String safeMethodName(String methodName) {
            if (isBlank(methodName)) {
                return "unknown";
            }
            return methodName.trim();
        }

        private static class PipelineState {
            private final int instanceId;
            private final String className;
            private final Set<String> observedMethods = new LinkedHashSet<String>();
            private final Set<String> stageTypes = new LinkedHashSet<String>();
            private final List<String> evidence = new ArrayList<String>();
            private String producedBy;
            private String lastError;

            private PipelineState(int instanceId, String className) {
                this.instanceId = instanceId;
                this.className = className;
            }
        }
    }

    public static class RuntimeExecutionSnapshotCollector {
        private static final int MAX_COLLECTION_SAMPLE = 24;
        private static final int MAX_OBJECT_FIELD_SCAN = 48;
        private static final int MAX_OBJECT_HIERARCHY_DEPTH = 6;
        private static final int MAX_EVENT_FIELD_CAPTURE = 40;
        private static final int MAX_STRING_LENGTH = 220;

        private final int maxEvents;
        private final int maxObjects;
        private final boolean debugRuntime;
        private final AtomicInteger eventSeq = new AtomicInteger(0);
        private final AtomicInteger droppedEvents = new AtomicInteger(0);
        private final AtomicInteger storedEvents = new AtomicInteger(0);
        private final ConcurrentLinkedQueue<RuntimeExecutionEvent> events =
                new ConcurrentLinkedQueue<RuntimeExecutionEvent>();
        private final Map<Integer, RuntimeObjectSnapshot> objects =
                new ConcurrentHashMap<Integer, RuntimeObjectSnapshot>();
        private final ThreadLocal<Deque<String>> stackByThread =
                new ThreadLocal<Deque<String>>() {
                    @Override
                    protected Deque<String> initialValue() {
                        return new ArrayDeque<String>();
                    }
                };
        private final ThreadLocal<Deque<FrameState>> frameByThread =
                new ThreadLocal<Deque<FrameState>>() {
                    @Override
                    protected Deque<FrameState> initialValue() {
                        return new ArrayDeque<FrameState>();
                    }
                };
        private final ThreadLocal<Set<Integer>> objectCaptureInFlight =
                new ThreadLocal<Set<Integer>>() {
                    @Override
                    protected Set<Integer> initialValue() {
                        return new LinkedHashSet<Integer>();
                    }
                };

        public RuntimeExecutionSnapshotCollector(int maxEvents, int maxObjects, boolean debugRuntime) {
            this.maxEvents = Math.max(maxEvents, 2000);
            this.maxObjects = Math.max(maxObjects, 1000);
            this.debugRuntime = debugRuntime;
        }

        public void onMethodEnter(String typeName, String methodName, Object self, Object[] args) {
            String methodId = methodId(typeName, methodName);
            Deque<String> stack = stackByThread.get();
            String parent = stack.peekLast();
            stack.addLast(methodId);
            Deque<FrameState> frameStack = frameByThread.get();
            frameStack.addLast(FrameState.forEnter(methodId, self, snapshotInstanceFieldSummaryMap(self)));

            RuntimeExecutionEvent event = new RuntimeExecutionEvent();
            event.seq = eventSeq.incrementAndGet();
            event.eventType = "ENTER";
            event.timestampMs = System.currentTimeMillis();
            event.threadId = Thread.currentThread().getId();
            event.threadName = Thread.currentThread().getName();
            event.methodId = methodId;
            event.parentMethodId = parent;
            event.stackDepth = stack.size();
            event.receiver = summarizeValue("this", self, true);
            event.receiverFields = snapshotInstanceFieldValues(self);
            event.arguments = summarizeArguments(args);
            appendEvent(event);
        }

        public void onMethodExit(String typeName,
                                 String methodName,
                                 Object self,
                                 Object[] args,
                                 Object returned,
                                 Throwable thrown) {
            onMethodExit(typeName, methodName, self, args, returned, thrown, false);
        }

        public void onMethodExit(String typeName,
                                 String methodName,
                                 Object self,
                                 Object[] args,
                                 Object returned,
                                 Throwable thrown,
                                 boolean softFailSuppressed) {
            String methodId = methodId(typeName, methodName);
            Deque<String> stack = stackByThread.get();
            Deque<FrameState> frameStack = frameByThread.get();
            FrameState frameState = popFrameState(frameStack, methodId);
            if (!stack.isEmpty()) {
                if (methodId.equals(stack.peekLast())) {
                    stack.removeLast();
                } else {
                    while (!stack.isEmpty()) {
                        String popped = stack.removeLast();
                        if (methodId.equals(popped)) {
                            break;
                        }
                    }
                }
            }

            RuntimeExecutionEvent event = new RuntimeExecutionEvent();
            event.seq = eventSeq.incrementAndGet();
            event.eventType = "EXIT";
            event.timestampMs = System.currentTimeMillis();
            event.threadId = Thread.currentThread().getId();
            event.threadName = Thread.currentThread().getName();
            event.methodId = methodId;
            event.parentMethodId = stack.peekLast();
            event.stackDepth = stack.size();
            event.receiver = summarizeValue("this", self, true);
            event.receiverFields = snapshotInstanceFieldValues(self);
            event.receiverFieldChanges = computeFieldChanges(frameState, self);
            event.arguments = summarizeArguments(args);
            if (returned != null) {
                event.returnValue = summarizeValue("return", returned, true);
            }
            if (thrown != null) {
                event.thrown = thrown.getClass().getName() + ": " + thrown.getMessage();
            }
            event.softFailSuppressed = softFailSuppressed;
            appendEvent(event);
        }

        public int getEventCount() {
            return eventSeq.get();
        }

        public int getDroppedEventCount() {
            return droppedEvents.get();
        }

        public int getObjectCount() {
            return objects.size();
        }

        public List<RuntimeExecutionEvent> snapshotEvents(int limit) {
            List<RuntimeExecutionEvent> result = new ArrayList<RuntimeExecutionEvent>();
            int max = limit <= 0 ? maxEvents : Math.min(limit, maxEvents);
            int count = 0;
            for (RuntimeExecutionEvent event : events) {
                result.add(event);
                count++;
                if (count >= max) {
                    break;
                }
            }
            return result;
        }

        public List<RuntimeObjectSnapshot> snapshotObjects(int limit) {
            List<RuntimeObjectSnapshot> list = new ArrayList<RuntimeObjectSnapshot>();
            list.addAll(objects.values());
            list.sort(Comparator.comparing((RuntimeObjectSnapshot s) -> s.typeName)
                    .thenComparingInt(s -> s.identityId));
            if (limit > 0 && list.size() > limit) {
                return new ArrayList<RuntimeObjectSnapshot>(list.subList(0, limit));
            }
            return list;
        }

        private void appendEvent(RuntimeExecutionEvent event) {
            if (event == null) {
                return;
            }
            if (storedEvents.get() >= maxEvents) {
                droppedEvents.incrementAndGet();
                return;
            }
            events.add(event);
            storedEvents.incrementAndGet();
        }

        private FrameState popFrameState(Deque<FrameState> frameStack, String methodId) {
            if (frameStack == null || frameStack.isEmpty()) {
                return null;
            }
            FrameState last = frameStack.peekLast();
            if (last != null && methodId.equals(last.methodId)) {
                return frameStack.removeLast();
            }
            while (!frameStack.isEmpty()) {
                FrameState candidate = frameStack.removeLast();
                if (methodId.equals(candidate.methodId)) {
                    return candidate;
                }
            }
            return null;
        }

        private List<RuntimeFieldValue> snapshotInstanceFieldValues(Object target) {
            if (target == null) {
                return Collections.emptyList();
            }
            List<RuntimeFieldValue> result = new ArrayList<RuntimeFieldValue>();
            int scanned = 0;
            int depth = 0;
            Class<?> current = target.getClass();
            while (current != null && current != Object.class && depth < MAX_OBJECT_HIERARCHY_DEPTH) {
                Field[] fields = current.getDeclaredFields();
                for (Field field : fields) {
                    if (field == null || Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    if (scanned++ >= MAX_EVENT_FIELD_CAPTURE) {
                        break;
                    }
                    field.setAccessible(true);
                    Object fieldValue;
                    try {
                        fieldValue = field.get(target);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    RuntimeFieldValue item = new RuntimeFieldValue();
                    item.ownerType = current.getName();
                    item.fieldName = field.getName();
                    item.value = summarizeValue("field", fieldValue, true);
                    result.add(item);
                }
                if (scanned >= MAX_EVENT_FIELD_CAPTURE) {
                    break;
                }
                current = current.getSuperclass();
                depth++;
            }
            result.sort(Comparator.comparing((RuntimeFieldValue f) -> f.ownerType)
                    .thenComparing(f -> f.fieldName));
            return result;
        }

        private Map<String, String> snapshotInstanceFieldSummaryMap(Object target) {
            if (target == null) {
                return Collections.emptyMap();
            }
            Map<String, String> summary = new LinkedHashMap<String, String>();
            for (RuntimeFieldValue value : snapshotInstanceFieldValues(target)) {
                if (value == null) {
                    continue;
                }
                String key = value.ownerType + "." + value.fieldName;
                summary.put(key, valueToSummary(value.value));
            }
            return summary;
        }

        private List<RuntimeFieldChange> computeFieldChanges(FrameState frameState, Object target) {
            if (frameState == null || frameState.receiverIdentityId == 0 || target == null) {
                return Collections.emptyList();
            }
            if (frameState.receiverIdentityId != System.identityHashCode(target)) {
                return Collections.emptyList();
            }
            Map<String, String> before = frameState.receiverFieldSummary == null
                    ? Collections.<String, String>emptyMap()
                    : frameState.receiverFieldSummary;
            Map<String, String> after = snapshotInstanceFieldSummaryMap(target);
            if (before.isEmpty() && after.isEmpty()) {
                return Collections.emptyList();
            }
            LinkedHashSet<String> keys = new LinkedHashSet<String>();
            keys.addAll(before.keySet());
            keys.addAll(after.keySet());
            List<RuntimeFieldChange> changes = new ArrayList<RuntimeFieldChange>();
            for (String key : keys) {
                String b = before.get(key);
                String a = after.get(key);
                if ((b == null && a == null) || (b != null && b.equals(a))) {
                    continue;
                }
                RuntimeFieldChange change = new RuntimeFieldChange();
                int dot = key.lastIndexOf('.');
                if (dot > 0) {
                    change.ownerType = key.substring(0, dot);
                    change.fieldName = key.substring(dot + 1);
                } else {
                    change.ownerType = "";
                    change.fieldName = key;
                }
                change.before = b == null ? "(absent)" : b;
                change.after = a == null ? "(absent)" : a;
                changes.add(change);
            }
            return changes;
        }

        private String valueToSummary(RuntimeValueInfo value) {
            if (value == null || isBlank(value.typeName) || "null".equals(value.typeName)) {
                return "null";
            }
            if (!isBlank(value.simpleValue)) {
                return value.typeName + ":" + value.simpleValue;
            }
            String base = value.typeName + "@" + value.identityId;
            if (value.size > 0) {
                return base + "(size=" + value.size + ")";
            }
            return base;
        }

        private List<RuntimeValueInfo> summarizeArguments(Object[] args) {
            if (args == null || args.length == 0) {
                return Collections.emptyList();
            }
            List<RuntimeValueInfo> values = new ArrayList<RuntimeValueInfo>();
            for (Object arg : args) {
                values.add(summarizeValue("arg", arg, true));
            }
            return values;
        }

        private RuntimeValueInfo summarizeValue(String kind, Object value, boolean captureObject) {
            RuntimeValueInfo info = new RuntimeValueInfo();
            info.kind = kind;
            if (value == null) {
                info.typeName = "null";
                info.identityId = 0;
                return info;
            }
            Class<?> type = value.getClass();
            info.typeName = type.getName();
            info.identityId = System.identityHashCode(value);
            if (isSimpleType(type)) {
                info.simpleValue = summarizeSimpleValue(value);
                return info;
            }
            if (type.isArray()) {
                int size = Array.getLength(value);
                info.size = size;
                info.elementTypes = sampleArrayElementTypes(value);
            } else if (value instanceof Collection) {
                Collection<?> collection = (Collection<?>) value;
                info.size = collection.size();
                info.elementTypes = sampleCollectionTypes(collection);
            } else if (value instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) value;
                info.size = map.size();
                info.mapKeyTypes = sampleMapKeyTypes(map);
                info.mapValueTypes = sampleMapValueTypes(map);
            } else {
                info.simpleValue = summarizeSimpleValue(value);
            }
            if (captureObject) {
                captureObjectSnapshot(value);
            }
            return info;
        }

        private void captureObjectSnapshot(Object value) {
            if (value == null) {
                return;
            }
            Class<?> type = value.getClass();
            if (isSimpleType(type)) {
                return;
            }
            int identityId = System.identityHashCode(value);
            if (objects.containsKey(identityId)) {
                return;
            }
            if (objects.size() >= maxObjects) {
                return;
            }
            Set<Integer> inFlight = objectCaptureInFlight.get();
            if (!inFlight.add(Integer.valueOf(identityId))) {
                return;
            }
            try {
                RuntimeObjectSnapshot snapshot = new RuntimeObjectSnapshot();
                snapshot.identityId = identityId;
                snapshot.typeName = type.getName();
                Class<?> superType = type.getSuperclass();
                snapshot.superTypeName = superType == null ? null : superType.getName();
                snapshot.interfaceTypes = new ArrayList<String>();
                for (Class<?> iface : type.getInterfaces()) {
                    if (iface != null) {
                        snapshot.interfaceTypes.add(iface.getName());
                    }
                }
                Collections.sort(snapshot.interfaceTypes);
                snapshot.fieldRuntimeTypes = new LinkedHashMap<String, String>();
                snapshot.fieldValueSummary = new LinkedHashMap<String, String>();
                snapshot.collectionElementTypes = new ArrayList<String>();
                snapshot.mapKeyTypes = new ArrayList<String>();
                snapshot.mapValueTypes = new ArrayList<String>();

                RuntimeObjectSnapshot existing = objects.putIfAbsent(identityId, snapshot);
                if (existing != null) {
                    return;
                }

                if (value instanceof Collection) {
                    snapshot.collectionElementTypes.addAll(sampleCollectionTypes((Collection<?>) value));
                } else if (value instanceof Map) {
                    snapshot.mapKeyTypes.addAll(sampleMapKeyTypes((Map<?, ?>) value));
                    snapshot.mapValueTypes.addAll(sampleMapValueTypes((Map<?, ?>) value));
                } else if (type.isArray()) {
                    snapshot.collectionElementTypes.addAll(sampleArrayElementTypes(value));
                }

                int scanned = 0;
                int hierarchyDepth = 0;
                Class<?> current = type;
                while (current != null && current != Object.class && hierarchyDepth < MAX_OBJECT_HIERARCHY_DEPTH) {
                    Field[] fields = current.getDeclaredFields();
                    for (Field field : fields) {
                        if (field == null || Modifier.isStatic(field.getModifiers())) {
                            continue;
                        }
                        if (scanned >= MAX_OBJECT_FIELD_SCAN) {
                            break;
                        }
                        scanned++;
                        field.setAccessible(true);
                        Object fieldValue;
                        try {
                            fieldValue = field.get(value);
                        } catch (Throwable ignored) {
                            continue;
                        }
                        String fieldKey = current.getSimpleName() + "." + field.getName();
                        RuntimeValueInfo fieldInfo = summarizeValue("field", fieldValue, true);
                        snapshot.fieldValueSummary.put(fieldKey, valueToSummary(fieldInfo));
                        if (fieldValue == null) {
                            continue;
                        }
                        snapshot.fieldRuntimeTypes.put(
                                fieldKey,
                                fieldValue.getClass().getName()
                        );
                    }
                    if (scanned >= MAX_OBJECT_FIELD_SCAN) {
                        break;
                    }
                    current = current.getSuperclass();
                    hierarchyDepth++;
                }

                if (debugRuntime) {
                    // lightweight marker, avoids flooding log with full object content
                    System.out.println("[RUNTIME_DEBUG] RUNTIME_OBJECT_SNAPSHOT type="
                            + snapshot.typeName + " id=" + snapshot.identityId
                            + " fields=" + snapshot.fieldRuntimeTypes.size());
                }
            } finally {
                inFlight.remove(Integer.valueOf(identityId));
            }
        }

        private List<String> sampleArrayElementTypes(Object array) {
            LinkedHashSet<String> types = new LinkedHashSet<String>();
            int length = Array.getLength(array);
            int upper = Math.min(length, MAX_COLLECTION_SAMPLE);
            for (int i = 0; i < upper; i++) {
                Object element = Array.get(array, i);
                if (element != null) {
                    types.add(element.getClass().getName());
                    captureObjectSnapshot(element);
                }
            }
            return new ArrayList<String>(types);
        }

        private List<String> sampleCollectionTypes(Collection<?> values) {
            LinkedHashSet<String> types = new LinkedHashSet<String>();
            int count = 0;
            for (Object value : values) {
                if (count++ >= MAX_COLLECTION_SAMPLE) {
                    break;
                }
                if (value != null) {
                    types.add(value.getClass().getName());
                    captureObjectSnapshot(value);
                }
            }
            return new ArrayList<String>(types);
        }

        private List<String> sampleMapKeyTypes(Map<?, ?> map) {
            LinkedHashSet<String> types = new LinkedHashSet<String>();
            int count = 0;
            for (Object key : map.keySet()) {
                if (count++ >= MAX_COLLECTION_SAMPLE) {
                    break;
                }
                if (key != null) {
                    types.add(key.getClass().getName());
                    captureObjectSnapshot(key);
                }
            }
            return new ArrayList<String>(types);
        }

        private List<String> sampleMapValueTypes(Map<?, ?> map) {
            LinkedHashSet<String> types = new LinkedHashSet<String>();
            int count = 0;
            for (Object value : map.values()) {
                if (count++ >= MAX_COLLECTION_SAMPLE) {
                    break;
                }
                if (value != null) {
                    types.add(value.getClass().getName());
                    captureObjectSnapshot(value);
                }
            }
            return new ArrayList<String>(types);
        }

        private String summarizeSimpleValue(Object value) {
            if (value == null) {
                return "null";
            }
            if (value instanceof CharSequence
                    || value instanceof Number
                    || value instanceof Boolean
                    || value instanceof Character
                    || value instanceof Enum) {
                String raw;
                try {
                    raw = String.valueOf(value);
                } catch (Throwable error) {
                    raw = value.getClass().getName() + "(toString-error)";
                }
                if (raw == null) {
                    return "null";
                }
                if (raw.length() > MAX_STRING_LENGTH) {
                    return raw.substring(0, MAX_STRING_LENGTH) + "...";
                }
                return raw;
            }
            if (value instanceof Class) {
                return ((Class<?>) value).getName();
            }
            if (value instanceof Throwable) {
                Throwable error = (Throwable) value;
                String message = error.getMessage();
                if (isBlank(message)) {
                    return error.getClass().getName();
                }
                if (message.length() > MAX_STRING_LENGTH) {
                    message = message.substring(0, MAX_STRING_LENGTH) + "...";
                }
                return error.getClass().getName() + ":" + message;
            }
            return value.getClass().getName() + "@" + System.identityHashCode(value);
        }

        private String methodId(String typeName, String methodName) {
            String t = isBlank(typeName) ? "unknown" : typeName;
            String m = isBlank(methodName) ? "unknown" : methodName;
            return t + "#" + m;
        }

        private static class FrameState {
            private final String methodId;
            private final int receiverIdentityId;
            private final Map<String, String> receiverFieldSummary;

            private FrameState(String methodId, int receiverIdentityId, Map<String, String> receiverFieldSummary) {
                this.methodId = methodId;
                this.receiverIdentityId = receiverIdentityId;
                this.receiverFieldSummary = receiverFieldSummary == null
                        ? Collections.<String, String>emptyMap()
                        : new LinkedHashMap<String, String>(receiverFieldSummary);
            }

            private static FrameState forEnter(String methodId, Object self, Map<String, String> fieldSummary) {
                int identity = self == null ? 0 : System.identityHashCode(self);
                return new FrameState(methodId, identity, fieldSummary);
            }
        }
    }

    public static class SoftFailController {
        private static final int DEFAULT_SAMPLE_LIMIT = 500;

        private final boolean enabled;
        private final int maxSuppressions;
        private final List<String> exceptionPrefixes;
        private final List<String> methodTokens;
        private final AtomicInteger suppressedCount = new AtomicInteger(0);
        private final AtomicInteger suppressedSeq = new AtomicInteger(0);
        private final ConcurrentLinkedQueue<SoftFailSuppressedSample> samples =
                new ConcurrentLinkedQueue<SoftFailSuppressedSample>();

        private SoftFailController(boolean enabled,
                                   int maxSuppressions,
                                   List<String> exceptionPrefixes,
                                   List<String> methodTokens) {
            this.enabled = enabled;
            this.maxSuppressions = maxSuppressions <= 0 ? 0 : maxSuppressions;
            this.exceptionPrefixes = exceptionPrefixes == null
                    ? Collections.<String>emptyList()
                    : new ArrayList<String>(exceptionPrefixes);
            this.methodTokens = methodTokens == null
                    ? Collections.<String>emptyList()
                    : new ArrayList<String>(methodTokens);
        }

        public static SoftFailController fromOptions(CliOptions options) {
            if (options == null) {
                return new SoftFailController(false, 0, Collections.<String>emptyList(), Collections.<String>emptyList());
            }
            return new SoftFailController(
                    options.softFail,
                    options.softFailMaxSuppressions,
                    options.softFailExceptionPrefixes,
                    options.softFailMethodTokens
            );
        }

        public boolean shouldSuppress(String typeName, String methodName, Throwable thrown) {
            if (!enabled || thrown == null) {
                return false;
            }
            if (thrown instanceof VirtualMachineError
                    || thrown instanceof ThreadDeath
                    || thrown instanceof LinkageError) {
                return false;
            }
            if (maxSuppressions > 0 && suppressedCount.get() >= maxSuppressions) {
                return false;
            }
            if (!exceptionPrefixes.isEmpty()) {
                String throwableClass = thrown.getClass().getName();
                boolean matched = false;
                for (String prefix : exceptionPrefixes) {
                    if (!isBlank(prefix) && throwableClass.startsWith(prefix)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    return false;
                }
            }
            if (!methodTokens.isEmpty()) {
                String method = methodName == null ? "" : methodName.toLowerCase(Locale.ROOT);
                String type = typeName == null ? "" : typeName.toLowerCase(Locale.ROOT);
                boolean matched = false;
                for (String token : methodTokens) {
                    if (isBlank(token)) {
                        continue;
                    }
                    String normalized = token.toLowerCase(Locale.ROOT);
                    if (method.contains(normalized) || type.contains(normalized)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    return false;
                }
            }
            return true;
        }

        public void recordSuppressed(String methodId, Throwable thrown) {
            int next = suppressedCount.incrementAndGet();
            if (maxSuppressions > 0 && next > maxSuppressions) {
                return;
            }
            if (samples.size() >= DEFAULT_SAMPLE_LIMIT) {
                return;
            }
            SoftFailSuppressedSample sample = new SoftFailSuppressedSample();
            sample.seq = suppressedSeq.incrementAndGet();
            sample.methodId = methodId == null ? "" : methodId;
            sample.throwableClass = thrown == null ? "" : thrown.getClass().getName();
            sample.message = thrown == null || thrown.getMessage() == null ? "" : thrown.getMessage();
            sample.timestampMs = System.currentTimeMillis();
            sample.threadName = Thread.currentThread().getName();
            samples.add(sample);
        }

        public int getSuppressedCount() {
            return suppressedCount.get();
        }

        public List<SoftFailSuppressedSample> snapshotSuppressedSamples(int limit) {
            int max = limit <= 0 ? DEFAULT_SAMPLE_LIMIT : Math.min(limit, DEFAULT_SAMPLE_LIMIT);
            List<SoftFailSuppressedSample> result = new ArrayList<SoftFailSuppressedSample>();
            int count = 0;
            for (SoftFailSuppressedSample sample : samples) {
                result.add(sample);
                count++;
                if (count >= max) {
                    break;
                }
            }
            return result;
        }

        public static Object defaultReturnValue(Class<?> returnType) {
            if (returnType == null || Void.TYPE.equals(returnType)) {
                return null;
            }
            if (!returnType.isPrimitive()) {
                return null;
            }
            if (Boolean.TYPE.equals(returnType)) {
                return Boolean.FALSE;
            }
            if (Byte.TYPE.equals(returnType)) {
                return Byte.valueOf((byte) 0);
            }
            if (Short.TYPE.equals(returnType)) {
                return Short.valueOf((short) 0);
            }
            if (Integer.TYPE.equals(returnType)) {
                return Integer.valueOf(0);
            }
            if (Long.TYPE.equals(returnType)) {
                return Long.valueOf(0L);
            }
            if (Float.TYPE.equals(returnType)) {
                return Float.valueOf(0.0f);
            }
            if (Double.TYPE.equals(returnType)) {
                return Double.valueOf(0.0d);
            }
            if (Character.TYPE.equals(returnType)) {
                return Character.valueOf('\0');
            }
            return null;
        }
    }

    public static class RuntimeTraceCollector {
        private final int maxCalls;
        private final AtomicInteger callCount = new AtomicInteger(0);
        private final ThreadLocal<Deque<String>> stackByThread =
                new ThreadLocal<Deque<String>>() {
                    @Override
                    protected Deque<String> initialValue() {
                        return new ArrayDeque<String>();
                    }
                };
        private final Map<String, RuntimeEdge> edges = new ConcurrentHashMap<String, RuntimeEdge>();

        public RuntimeTraceCollector(int maxCalls) {
            this.maxCalls = maxCalls;
        }

        public void onEnter(String methodId) {
            int current = callCount.incrementAndGet();
            if (current > maxCalls) {
                debugAdviceLine("COLLECTOR_ON_ENTER_OVERFLOW methodId=" + methodId
                        + " callCount=" + current + " maxCalls=" + maxCalls);
                return;
            }
            Deque<String> stack = stackByThread.get();
            String from = stack.peekLast();
            if (from != null) {
                String key = from + "->" + methodId;
                edges.compute(key, (k, existing) -> {
                    if (existing == null) {
                        return new RuntimeEdge(from, methodId, 1);
                    }
                    existing.count++;
                    return existing;
                });
            }
            stack.addLast(methodId);
            debugAdviceLine("COLLECTOR_ON_ENTER methodId=" + methodId
                    + " callCount=" + current
                    + " stackDepth=" + stack.size()
                    + " collector=" + System.identityHashCode(this));
        }

        public void onExit(String methodId) {
            Deque<String> stack = stackByThread.get();
            if (stack.isEmpty()) {
                return;
            }
            if (methodId.equals(stack.peekLast())) {
                stack.removeLast();
                return;
            }
            while (!stack.isEmpty()) {
                String popped = stack.removeLast();
                if (methodId.equals(popped)) {
                    break;
                }
            }
        }

        public int getCallCount() {
            return callCount.get();
        }

        public List<RuntimeEdge> snapshotEdges() {
            List<RuntimeEdge> list = new ArrayList<RuntimeEdge>();
            list.addAll(edges.values());
            list.sort(Comparator.comparing((RuntimeEdge e) -> e.from).thenComparing(e -> e.to));
            return list;
        }
    }

    public static class RuntimeEdge {
        public final String from;
        public final String to;
        public int count;

        public RuntimeEdge(String from, String to, int count) {
            this.from = from;
            this.to = to;
            this.count = count;
        }
    }

    public static class RuntimeTraceReport {
        public String generatedAt;
        public String entryClass;
        public String entryMethod;
        public String rootMethodId;
        public List<String> classesRoots;
        public List<String> classpathEntries;
        public List<String> tracePrefixes;
        public List<String> arguments;
        public String result;
        public String error;
        public String errorStack;
        public long durationMs;
        public int callCount;
        public List<RuntimeEdge> edges;
        public int discoveredProjectClassCount;
        public List<String> loadedProjectClasses;
        public List<String> skippedProjectClasses;
        public boolean useSpringContext;
        public boolean springContextActive;
        public int springBeanDefinitionCount;
        public List<String> springBeanDefinitions;
        public List<RuntimeBeanTypeInfo> sandboxBeanTypeInfos;
        public List<RuntimeBeanTypeInfo> springBeanTypeInfos;
        public int pipelineObservationCount;
        public List<RuntimePipelineTypeInfo> pipelineTypeInfos;
        public int runtimeEventCount;
        public int runtimeDroppedEvents;
        public int runtimeObjectCount;
        public List<RuntimeExecutionEvent> runtimeEvents;
        public List<RuntimeObjectSnapshot> runtimeObjects;
        public boolean softFailEnabled;
        public int softFailMaxSuppressions;
        public List<String> softFailExceptionPrefixes;
        public List<String> softFailMethodTokens;
        public int softFailSuppressedCount;
        public List<SoftFailSuppressedSample> softFailSuppressedSamples;
    }

    public static class RuntimeBeanTypeInfo {
        public String source;
        public String beanName;
        public String concreteClass;
        public List<String> assignableTypes;
        public boolean instantiated;
    }

    public static class RuntimePipelineTypeInfo {
        public int instanceId;
        public String className;
        public String producedBy;
        public List<String> observedMethods;
        public List<String> stageTypes;
        public String lastError;
        public List<String> evidence;
    }

    public static class RuntimeExecutionEvent {
        public int seq;
        public String eventType;
        public long timestampMs;
        public long threadId;
        public String threadName;
        public String methodId;
        public String parentMethodId;
        public int stackDepth;
        public RuntimeValueInfo receiver;
        public List<RuntimeFieldValue> receiverFields;
        public List<RuntimeFieldChange> receiverFieldChanges;
        public List<RuntimeValueInfo> arguments;
        public RuntimeValueInfo returnValue;
        public String thrown;
        public boolean softFailSuppressed;
    }

    public static class SoftFailSuppressedSample {
        public int seq;
        public String methodId;
        public String throwableClass;
        public String message;
        public long timestampMs;
        public String threadName;
    }

    public static class RuntimeFieldValue {
        public String ownerType;
        public String fieldName;
        public RuntimeValueInfo value;
    }

    public static class RuntimeFieldChange {
        public String ownerType;
        public String fieldName;
        public String before;
        public String after;
    }

    public static class RuntimeValueInfo {
        public String kind;
        public String typeName;
        public int identityId;
        public String simpleValue;
        public int size;
        public List<String> elementTypes;
        public List<String> mapKeyTypes;
        public List<String> mapValueTypes;
    }

    public static class RuntimeObjectSnapshot {
        public int identityId;
        public String typeName;
        public String superTypeName;
        public List<String> interfaceTypes;
        public Map<String, String> fieldRuntimeTypes;
        public Map<String, String> fieldValueSummary;
        public List<String> collectionElementTypes;
        public List<String> mapKeyTypes;
        public List<String> mapValueTypes;
    }

    public static class ProjectClassIndex {
        public final Set<String> classNames = new LinkedHashSet<String>();
        public final Map<String, String> sourceByClassName = new LinkedHashMap<String, String>();

        public void add(String className, Path classFile) {
            if (className == null || className.isEmpty()) {
                return;
            }
            classNames.add(className);
            if (classFile != null) {
                sourceByClassName.put(className, classFile.toAbsolutePath().toString());
            }
        }

        public boolean contains(String className) {
            return classNames.contains(className);
        }
    }

    public static class ProjectAwareClassLoader extends URLClassLoader {
        private final ProjectClassIndex projectClassIndex;
        private final List<String> tracePrefixes;
        private final boolean debugRuntime;
        private final Set<String> loadedProjectClasses =
                Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
        private final Set<String> failedProjectClasses =
                Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

        public ProjectAwareClassLoader(URL[] urls,
                                       ClassLoader parent,
                                       ProjectClassIndex projectClassIndex,
                                       List<String> tracePrefixes,
                                       boolean debugRuntime) {
            super(urls, parent);
            this.projectClassIndex = projectClassIndex == null ? new ProjectClassIndex() : projectClassIndex;
            this.tracePrefixes = tracePrefixes == null ? Collections.<String>emptyList() : new ArrayList<String>(tracePrefixes);
            this.debugRuntime = debugRuntime;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> alreadyLoaded = findLoadedClass(name);
                if (alreadyLoaded != null) {
                    if (resolve) {
                        resolveClass(alreadyLoaded);
                    }
                    return alreadyLoaded;
                }

                boolean projectClass = isProjectClass(name);
                if (projectClass) {
                    try {
                        Class<?> loaded = findClass(name);
                        if (resolve) {
                            resolveClass(loaded);
                        }
                        loadedProjectClasses.add(name);
                        if (debugRuntime) {
                            String source = projectClassIndex.sourceByClassName.getOrDefault(name, "unknown");
                            System.out.println("[RUNTIME_DEBUG] PROJECT_CLASS_LOAD_OK class=" + name + " source=" + source);
                        }
                        return loaded;
                    } catch (ClassNotFoundException ignored) {
                        failedProjectClasses.add(name);
                        if (debugRuntime) {
                            System.out.println("[RUNTIME_DEBUG] PROJECT_CLASS_LOAD_FALLBACK class=" + name);
                        }
                    } catch (LinkageError linkageError) {
                        failedProjectClasses.add(name);
                        if (debugRuntime) {
                            System.out.println("[RUNTIME_DEBUG] PROJECT_CLASS_LOAD_SKIP class=" + name
                                    + " error=" + linkageError.getClass().getName() + ": " + linkageError.getMessage());
                        }
                    }
                }

                try {
                    Class<?> loaded = super.loadClass(name, resolve);
                    if (projectClass) {
                        loadedProjectClasses.add(name);
                    }
                    return loaded;
                } catch (ClassNotFoundException error) {
                    if (projectClass) {
                        failedProjectClasses.add(name);
                        if (debugRuntime) {
                            System.out.println("[RUNTIME_DEBUG] PROJECT_CLASS_LOAD_SKIP class="
                                    + name + " error=ClassNotFoundException");
                        }
                    }
                    throw error;
                }
            }
        }

        public List<String> snapshotLoadedProjectClasses() {
            List<String> values = new ArrayList<String>(loadedProjectClasses);
            Collections.sort(values);
            return values;
        }

        public List<String> snapshotFailedProjectClasses() {
            Set<String> unresolved = new LinkedHashSet<String>(failedProjectClasses);
            unresolved.removeAll(loadedProjectClasses);
            List<String> values = new ArrayList<String>(unresolved);
            Collections.sort(values);
            return values;
        }

        private boolean isProjectClass(String className) {
            if (className == null || className.isEmpty()) {
                return false;
            }
            if (isParentFirstRuntimeClass(className)) {
                return false;
            }
            if (projectClassIndex.contains(className)) {
                return true;
            }
            for (String prefix : tracePrefixes) {
                if (className.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        private boolean isParentFirstRuntimeClass(String className) {
            String runtimeClassName = RuntimeSandboxSimulator.class.getName();
            return className.equals(runtimeClassName)
                    || className.startsWith(runtimeClassName + "$");
        }
    }

    public static class MethodSelector {
        public final String methodName;
        public final Integer argCount;

        public MethodSelector(String methodName, Integer argCount) {
            this.methodName = methodName;
            this.argCount = argCount;
        }

        public static MethodSelector parse(String raw) {
            if (raw == null || raw.trim().isEmpty()) {
                return new MethodSelector("", null);
            }
            String value = raw.trim();
            int hashIndex = value.lastIndexOf('#');
            if (hashIndex >= 0 && hashIndex + 1 < value.length()) {
                value = value.substring(hashIndex + 1).trim();
            }
            Integer argCount = null;
            int slashIndex = value.lastIndexOf('/');
            if (slashIndex > 0 && slashIndex + 1 < value.length()) {
                try {
                    argCount = Integer.parseInt(value.substring(slashIndex + 1));
                    value = value.substring(0, slashIndex).trim();
                } catch (NumberFormatException ignored) {
                    argCount = null;
                }
            }
            return new MethodSelector(value, argCount);
        }

        public boolean matches(String methodName, int arity) {
            if (!this.methodName.equals(methodName)) {
                return false;
            }
            return argCount == null || argCount.intValue() == arity;
        }
    }

    public static class SandboxBeanFactory implements BeanProvider {
        private final ClassLoader classLoader;
        private final CliOptions options;
        private final List<String> discoveredClassNames;
        private final Map<String, Class<?>> loadedByClassName = new LinkedHashMap<String, Class<?>>();
        private final Set<String> failedClassNames = new LinkedHashSet<String>();
        private final Map<Class<?>, Object> singletonByConcreteClass = new LinkedHashMap<Class<?>, Object>();
        private final Set<Class<?>> creating = new LinkedHashSet<Class<?>>();
        private final Map<Class<?>, String> beanCreateFailures = new LinkedHashMap<Class<?>, String>();
        private final Map<String, List<Class<?>>> beanClassesByName = new LinkedHashMap<String, List<Class<?>>>();
        private final Map<Class<?>, Set<String>> beanNamesByClass = new LinkedHashMap<Class<?>, Set<String>>();
        private final Map<String, Set<String>> beanNameSources = new LinkedHashMap<String, Set<String>>();
        private final Set<Class<?>> primaryBeanClasses = new LinkedHashSet<Class<?>>();
        private final Map<String, String> xmlAliasToName = new LinkedHashMap<String, String>();
        private int classLoadFailLogs = 0;
        private int beanDebugLines = 0;
        private static final int MAX_CLASS_LOAD_FAIL_LOGS = 60;
        private static final int MAX_BEAN_DEBUG_LINES = 800;
        private static final Set<String> COMPONENT_ANNOTATION_NAMES = new LinkedHashSet<String>(
                Arrays.asList("Component", "Service", "Repository", "Controller", "RestController", "Configuration")
        );

        public SandboxBeanFactory(ClassLoader classLoader,
                                  CliOptions options,
                                  ProjectClassIndex projectClassIndex) throws IOException {
            this.classLoader = classLoader;
            this.options = options;
            if (projectClassIndex != null && !projectClassIndex.classNames.isEmpty()) {
                this.discoveredClassNames = new ArrayList<String>(projectClassIndex.classNames);
                Collections.sort(this.discoveredClassNames);
            } else {
                this.discoveredClassNames = scanClassNames(options.classesRoots, options.debugRuntime);
            }
            initializeSpringBeanMetadata();
        }

        public int getScannedClassCount() {
            return discoveredClassNames.size();
        }

        @Override
        public String providerName() {
            return "SandboxBeanFactory";
        }

        public List<RuntimeBeanTypeInfo> snapshotBeanTypeInfos(int limit) {
            List<RuntimeBeanTypeInfo> result = new ArrayList<RuntimeBeanTypeInfo>();
            LinkedHashSet<String> dedup = new LinkedHashSet<String>();

            for (Map.Entry<String, List<Class<?>>> entry : beanClassesByName.entrySet()) {
                String beanName = entry.getKey();
                List<Class<?>> classes = entry.getValue();
                if (classes == null || classes.isEmpty()) {
                    continue;
                }
                for (Class<?> clazz : classes) {
                    if (clazz == null) {
                        continue;
                    }
                    if (limit > 0 && result.size() >= limit) {
                        return result;
                    }
                    String key = beanName + "|" + clazz.getName();
                    if (!dedup.add(key)) {
                        continue;
                    }
                    RuntimeBeanTypeInfo info = new RuntimeBeanTypeInfo();
                    info.source = "SandboxBeanFactory";
                    info.beanName = beanName;
                    info.concreteClass = clazz.getName();
                    info.assignableTypes = collectAssignableTypeNames(clazz);
                    info.instantiated = singletonByConcreteClass.containsKey(clazz);
                    result.add(info);
                }
            }

            for (Map.Entry<Class<?>, Object> entry : singletonByConcreteClass.entrySet()) {
                Class<?> clazz = entry.getKey();
                if (clazz == null) {
                    continue;
                }
                if (limit > 0 && result.size() >= limit) {
                    return result;
                }
                Set<String> names = beanNamesByClass.getOrDefault(clazz, Collections.<String>emptySet());
                if (names.isEmpty()) {
                    String key = "(auto)|" + clazz.getName();
                    if (!dedup.add(key)) {
                        continue;
                    }
                    RuntimeBeanTypeInfo info = new RuntimeBeanTypeInfo();
                    info.source = "SandboxBeanFactory";
                    info.beanName = "(auto)";
                    info.concreteClass = clazz.getName();
                    info.assignableTypes = collectAssignableTypeNames(clazz);
                    info.instantiated = true;
                    result.add(info);
                    continue;
                }
                List<String> sortedNames = new ArrayList<String>(names);
                Collections.sort(sortedNames);
                for (String beanName : sortedNames) {
                    if (limit > 0 && result.size() >= limit) {
                        return result;
                    }
                    String key = beanName + "|" + clazz.getName();
                    if (!dedup.add(key)) {
                        continue;
                    }
                    RuntimeBeanTypeInfo info = new RuntimeBeanTypeInfo();
                    info.source = "SandboxBeanFactory";
                    info.beanName = beanName;
                    info.concreteClass = clazz.getName();
                    info.assignableTypes = collectAssignableTypeNames(clazz);
                    info.instantiated = true;
                    result.add(info);
                }
            }
            return result;
        }

        private void debugBean(String pattern, Object... args) {
            if (!options.debugRuntime) {
                return;
            }
            if (beanDebugLines < MAX_BEAN_DEBUG_LINES) {
                String message = (args == null || args.length == 0)
                        ? pattern
                        : String.format(Locale.ROOT, pattern, args);
                System.out.println("[RUNTIME_DEBUG] " + message);
                beanDebugLines++;
                return;
            }
            if (beanDebugLines == MAX_BEAN_DEBUG_LINES) {
                System.out.println("[RUNTIME_DEBUG] BEAN_DEBUG_LOG_SUPPRESSED additional logs omitted");
                beanDebugLines++;
            }
        }

        public List<String> suggestClassNames(String missingClass, int limit) {
            if (missingClass == null || missingClass.trim().isEmpty() || discoveredClassNames.isEmpty()) {
                return Collections.emptyList();
            }
            String normalized = missingClass.trim().replace('/', '.');
            String normalizedLower = normalized.toLowerCase(Locale.ROOT);
            String simpleName = normalized;
            int dot = normalized.lastIndexOf('.');
            if (dot >= 0 && dot + 1 < normalized.length()) {
                simpleName = normalized.substring(dot + 1);
            }
            String simpleLower = simpleName.toLowerCase(Locale.ROOT);

            LinkedHashSet<String> results = new LinkedHashSet<String>();
            for (String candidate : discoveredClassNames) {
                if (results.size() >= limit) {
                    break;
                }
                String candidateLower = candidate.toLowerCase(Locale.ROOT);
                String candidateSimple = candidate;
                int candidateDot = candidate.lastIndexOf('.');
                if (candidateDot >= 0 && candidateDot + 1 < candidate.length()) {
                    candidateSimple = candidate.substring(candidateDot + 1);
                }
                String candidateSimpleLower = candidateSimple.toLowerCase(Locale.ROOT);
                if (candidateLower.equals(normalizedLower)
                        || candidateSimpleLower.equals(simpleLower)
                        || candidateLower.contains(simpleLower)
                        || candidateLower.contains(normalizedLower)) {
                    results.add(candidate);
                }
            }
            return new ArrayList<String>(results);
        }

        @Override
        public Object getBean(Class<?> requestedType) {
            debugBean("BEAN_GET requestType=%s", requestedType == null ? "null" : requestedType.getName());
            if (requestedType == null) {
                return null;
            }
            List<Class<?>> candidates = resolveImplementationCandidates(requestedType, "", "");
            if (candidates.isEmpty()) {
                if (requestedType.isInterface()) {
                    debugBean("BEAN_GET_MOCK requestType=%s reason=no-impl", requestedType.getName());
                    return createInterfaceMock(requestedType);
                }
                debugBean("BEAN_GET_FAIL requestType=%s reason=no-candidates", requestedType.getName());
                return null;
            }

            for (Class<?> candidate : candidates) {
                if (candidate == null) {
                    continue;
                }
                Object bean = getOrCreateConcreteBean(candidate, requestedType, "GET");
                if (bean != null) {
                    return bean;
                }
            }
            if (requestedType.isInterface()) {
                debugBean("BEAN_GET_MOCK requestType=%s reason=all-candidates-failed", requestedType.getName());
                return createInterfaceMock(requestedType);
            }
            debugBean("BEAN_GET_FAIL requestType=%s reason=all-candidates-failed", requestedType.getName());
            return null;
        }

        private void injectFields(Object instance, Class<?> type) throws Exception {
            Class<?> current = type;
            while (current != null && current != Object.class) {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                        continue;
                    }
                    if (isSimpleType(field.getType())) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object currentValue = field.get(instance);
                    if (currentValue != null) {
                        continue;
                    }
                    InjectionHint hint = resolveInjectionHint(field);
                    debugBean(
                            "BEAN_INJECT_FIELD owner=%s field=%s fieldType=%s hintName=%s qualifier=%s explicit=%s",
                            type.getName(),
                            field.getName(),
                            field.getType().getName(),
                            hint.preferredBeanName,
                            hint.qualifier,
                            String.valueOf(hint.explicitInjection)
                    );
                    Object dependency = resolveDependency(field.getType(), field.getName(), hint);
                    if (dependency != null) {
                        field.set(instance, dependency);
                        debugBean(
                                "BEAN_INJECT_FIELD_OK owner=%s field=%s impl=%s",
                                type.getName(),
                                field.getName(),
                                dependency.getClass().getName()
                        );
                    } else {
                        debugBean(
                                "BEAN_INJECT_FIELD_SKIP owner=%s field=%s reason=dependency-null",
                                type.getName(),
                                field.getName()
                        );
                    }
                }
                current = current.getSuperclass();
            }
        }

        private Object resolveDependency(Class<?> dependencyType, String fieldName, InjectionHint hint) {
            debugBean(
                    "BEAN_RESOLVE type=%s field=%s hintName=%s qualifier=%s explicit=%s",
                    dependencyType == null ? "null" : dependencyType.getName(),
                    fieldName,
                    hint == null ? "" : hint.preferredBeanName,
                    hint == null ? "" : hint.qualifier,
                    String.valueOf(hint != null && hint.explicitInjection)
            );
            String preferredName = hint == null ? "" : hint.preferredBeanName;
            if (isBlank(preferredName)) {
                preferredName = fieldName;
            }
            if (hint != null && !isBlank(hint.preferredBeanName)) {
                Class<?> namedClass = resolveBeanClassByName(hint.preferredBeanName, dependencyType);
                if (namedClass != null) {
                    debugBean("BEAN_RESOLVE_HIT strategy=by-resource-name bean=%s type=%s",
                            hint.preferredBeanName, namedClass.getName());
                    return getOrCreateConcreteBean(namedClass, dependencyType, "RESOURCE_NAME");
                }
            }
            if (hint != null && !isBlank(hint.qualifier)) {
                Class<?> qualifierClass = resolveBeanClassByName(hint.qualifier, dependencyType);
                if (qualifierClass != null) {
                    debugBean("BEAN_RESOLVE_HIT strategy=by-qualifier bean=%s type=%s",
                            hint.qualifier, qualifierClass.getName());
                    return getOrCreateConcreteBean(qualifierClass, dependencyType, "QUALIFIER");
                }
            }
            if (dependencyType.isInterface()) {
                List<Class<?>> implCandidates = resolveImplementationCandidates(dependencyType, fieldName, preferredName);
                if (implCandidates.isEmpty()) {
                    debugBean("BEAN_RESOLVE_MOCK type=%s reason=no-impl", dependencyType.getName());
                    return createInterfaceMock(dependencyType);
                }
                for (Class<?> impl : implCandidates) {
                    if (impl == null) {
                        continue;
                    }
                    debugBean("BEAN_RESOLVE_TRY strategy=interface-impl type=%s impl=%s",
                            dependencyType.getName(), impl.getName());
                    Object resolved = getOrCreateConcreteBean(impl, dependencyType, "INTERFACE_IMPL");
                    if (resolved != null) {
                        debugBean("BEAN_RESOLVE_HIT strategy=interface-impl type=%s impl=%s",
                                dependencyType.getName(), impl.getName());
                        return resolved;
                    }
                    debugBean("BEAN_RESOLVE_BRANCH_FAIL strategy=interface-impl type=%s impl=%s", dependencyType.getName(), impl.getName());
                }
                debugBean("BEAN_RESOLVE_MOCK type=%s reason=all-candidates-failed", dependencyType.getName());
                return createInterfaceMock(dependencyType);
            }

            if (Modifier.isAbstract(dependencyType.getModifiers())) {
                List<Class<?>> implCandidates = resolveImplementationCandidates(dependencyType, fieldName, preferredName);
                if (implCandidates.isEmpty()) {
                    debugBean("BEAN_RESOLVE_SKIP type=%s reason=abstract-no-impl", dependencyType.getName());
                    return null;
                }
                for (Class<?> impl : implCandidates) {
                    if (impl == null) {
                        continue;
                    }
                    debugBean("BEAN_RESOLVE_TRY strategy=abstract-impl type=%s impl=%s",
                            dependencyType.getName(), impl.getName());
                    Object resolved = getOrCreateConcreteBean(impl, dependencyType, "ABSTRACT_IMPL");
                    if (resolved != null) {
                        debugBean("BEAN_RESOLVE_HIT strategy=abstract-impl type=%s impl=%s",
                                dependencyType.getName(), impl.getName());
                        return resolved;
                    }
                    debugBean("BEAN_RESOLVE_BRANCH_FAIL strategy=abstract-impl type=%s impl=%s", dependencyType.getName(), impl.getName());
                }
                debugBean("BEAN_RESOLVE_SKIP type=%s reason=abstract-all-candidates-failed", dependencyType.getName());
                return null;
            }
            debugBean("BEAN_RESOLVE_HIT strategy=concrete type=%s", dependencyType.getName());
            return getOrCreateConcreteBean(dependencyType, dependencyType, "CONCRETE");
        }

        private Object instantiate(Class<?> targetClass) throws Exception {
            Constructor<?> noArg = null;
            for (Constructor<?> constructor : targetClass.getDeclaredConstructors()) {
                if (constructor.getParameterCount() == 0) {
                    noArg = constructor;
                    break;
                }
            }
            if (noArg != null) {
                noArg.setAccessible(true);
                return noArg.newInstance();
            }

            List<Constructor<?>> constructors = Arrays.asList(targetClass.getDeclaredConstructors());
            constructors = constructors.stream()
                    .sorted(Comparator.comparingInt(Constructor::getParameterCount))
                    .collect(Collectors.toList());
            Constructor<?> autowired = findAnnotatedConstructor(constructors, "Autowired");
            if (autowired == null) {
                autowired = findAnnotatedConstructor(constructors, "Inject");
            }
            if (autowired != null) {
                constructors = new ArrayList<Constructor<?>>(constructors);
                constructors.remove(autowired);
                constructors.add(0, autowired);
            }
            for (Constructor<?> constructor : constructors) {
                Object[] args = new Object[constructor.getParameterCount()];
                boolean failed = false;
                for (int i = 0; i < constructor.getParameterCount(); i++) {
                    Class<?> parameterType = constructor.getParameterTypes()[i];
                    if (isSimpleType(parameterType)) {
                        Object primitiveDefault = defaultValue(parameterType);
                        if (primitiveDefault == null && parameterType.isPrimitive()) {
                            failed = true;
                            break;
                        }
                        args[i] = primitiveDefault;
                        continue;
                    }
                    Object dep = resolveDependency(parameterType, parameterType.getSimpleName(), InjectionHint.none());
                    if (dep == null && parameterType.isPrimitive()) {
                        failed = true;
                        break;
                    }
                    args[i] = dep;
                }
                if (failed) {
                    continue;
                }
                constructor.setAccessible(true);
                return constructor.newInstance(args);
            }
            throw new IllegalStateException("No suitable constructor for " + targetClass.getName());
        }

        private List<Class<?>> resolveImplementationCandidates(Class<?> requestedType,
                                                               String fieldName,
                                                               String preferredBeanName) {
            if (requestedType == null) {
                return Collections.emptyList();
            }
            if (!requestedType.isInterface() && !Modifier.isAbstract(requestedType.getModifiers())) {
                return Collections.singletonList(requestedType);
            }
            if (!isBlank(preferredBeanName)) {
                Class<?> byName = resolveBeanClassByName(preferredBeanName, requestedType);
                if (byName != null) {
                    debugBean(
                            "BEAN_IMPL_SELECT strategy=bean-name requested=%s field=%s bean=%s selected=%s",
                            requestedType.getName(),
                            fieldName,
                            preferredBeanName,
                            byName.getName()
                    );
                    return Collections.singletonList(byName);
                }
            }
            List<Class<?>> candidates = new ArrayList<Class<?>>();
            for (String className : discoveredClassNames) {
                Class<?> candidate = loadClassByName(className);
                if (candidate == null) {
                    continue;
                }
                if (candidate.isInterface() || Modifier.isAbstract(candidate.getModifiers())) {
                    continue;
                }
                if (!requestedType.isAssignableFrom(candidate)) {
                    continue;
                }
                candidates.add(candidate);
            }
            if (candidates.isEmpty()) {
                debugBean(
                        "BEAN_IMPL_SELECT_FAIL requested=%s field=%s preferred=%s candidates=0",
                        requestedType.getName(),
                        fieldName,
                        preferredBeanName
                );
                return Collections.emptyList();
            }
            String normalizedFieldName = normalizeBeanName(fieldName);
            String normalizedPreferredName = normalizeBeanName(preferredBeanName);
            List<String> candidateWithScores = new ArrayList<String>();
            for (Class<?> candidate : candidates) {
                int score = scoreBeanCandidate(candidate, normalizedFieldName, normalizedPreferredName);
                candidateWithScores.add(candidate.getName() + "(" + score + ")");
            }
            candidates.sort((left, right) -> {
                int scoreDiff = Integer.compare(
                        scoreBeanCandidate(right, normalizedFieldName, normalizedPreferredName),
                        scoreBeanCandidate(left, normalizedFieldName, normalizedPreferredName)
                );
                if (scoreDiff != 0) {
                    return scoreDiff;
                }
                return left.getName().compareTo(right.getName());
            });
            debugBean(
                    "BEAN_IMPL_CANDIDATES requested=%s field=%s preferred=%s candidates=%s",
                    requestedType.getName(),
                    fieldName,
                    preferredBeanName,
                    candidateWithScores
            );
            debugBean(
                    "BEAN_IMPL_SELECT strategy=scored requested=%s field=%s preferred=%s selected=%s fallbackCount=%d",
                    requestedType.getName(),
                    fieldName,
                    preferredBeanName,
                    candidates.get(0).getName(),
                    candidates.size() - 1
            );
            return candidates;
        }

        private Object getOrCreateConcreteBean(Class<?> targetClass,
                                               Class<?> requestedType,
                                               String reason) {
            if (targetClass == null) {
                return null;
            }
            Object existing = singletonByConcreteClass.get(targetClass);
            if (existing != null) {
                debugBean(
                        "BEAN_GET_HIT class=%s requested=%s reason=%s",
                        targetClass.getName(),
                        requestedType == null ? "null" : requestedType.getName(),
                        reason
                );
                return existing;
            }
            if (!creating.add(targetClass)) {
                debugBean("BEAN_GET_REENTRANT class=%s requested=%s reason=%s",
                        targetClass.getName(),
                        requestedType == null ? "null" : requestedType.getName(),
                        reason);
                return singletonByConcreteClass.get(targetClass);
            }
            try {
                debugBean("BEAN_CREATE class=%s requested=%s reason=%s",
                        targetClass.getName(),
                        requestedType == null ? "null" : requestedType.getName(),
                        reason);
                Object instance = instantiate(targetClass);
                singletonByConcreteClass.put(targetClass, instance);
                injectFields(instance, targetClass);
                beanCreateFailures.remove(targetClass);
                debugBean("BEAN_CREATE_DONE class=%s requested=%s reason=%s",
                        targetClass.getName(),
                        requestedType == null ? "null" : requestedType.getName(),
                        reason);
                return instance;
            } catch (Throwable error) {
                String failure = error.getClass().getName() + ": " + error.getMessage();
                beanCreateFailures.put(targetClass, failure);
                debugBean("BEAN_CREATE_BRANCH_FAIL class=%s requested=%s reason=%s error=%s",
                        targetClass.getName(),
                        requestedType == null ? "null" : requestedType.getName(),
                        reason,
                        failure);
                return null;
            } finally {
                creating.remove(targetClass);
            }
        }

        private Object createInterfaceMock(Class<?> iface) {
            InvocationHandler handler = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    return defaultValue(method.getReturnType());
                }
            };
            return Proxy.newProxyInstance(classLoader, new Class[]{iface}, handler);
        }

        private Class<?> loadClassByName(String className) {
            if (className == null || className.isEmpty()) {
                return null;
            }
            Class<?> cached = loadedByClassName.get(className);
            if (cached != null) {
                return cached;
            }
            if (failedClassNames.contains(className)) {
                return null;
            }
            try {
                Class<?> loaded = Class.forName(className, false, classLoader);
                loadedByClassName.put(className, loaded);
                return loaded;
            } catch (Throwable error) {
                failedClassNames.add(className);
                if (options.debugRuntime && classLoadFailLogs < MAX_CLASS_LOAD_FAIL_LOGS) {
                    System.out.println("[RUNTIME_DEBUG] BEAN_CLASS_LOAD_SKIP class="
                            + className + " error=" + error.getClass().getName() + ": " + error.getMessage());
                } else if (options.debugRuntime && classLoadFailLogs == MAX_CLASS_LOAD_FAIL_LOGS) {
                    System.out.println("[RUNTIME_DEBUG] BEAN_CLASS_LOAD_SKIP additional errors suppressed");
                }
                classLoadFailLogs++;
                return null;
            }
        }

        private void initializeSpringBeanMetadata() {
            for (String className : discoveredClassNames) {
                Class<?> candidate = loadClassByName(className);
                if (candidate == null) {
                    continue;
                }
                registerClassLevelSpringBeans(candidate);
                registerBeanMethods(candidate);
            }
            loadXmlBeanMetadata();
            if (options.debugRuntime) {
                System.out.println("[RUNTIME_DEBUG] SPRING_BEAN_METADATA names="
                        + beanClassesByName.size() + " primary=" + primaryBeanClasses.size());
            }
            logBeanRegistrySnapshot();
        }

        private void registerClassLevelSpringBeans(Class<?> clazz) {
            if (clazz == null) {
                return;
            }
            if (!isComponentClass(clazz)) {
                return;
            }
            String explicitName = extractComponentName(clazz);
            if (isBlank(explicitName)) {
                explicitName = decapitalize(clazz.getSimpleName());
            }
            registerBeanName(explicitName, clazz, "ANNOTATION:class");
            if (hasAnnotation(clazz.getAnnotations(), "Primary")) {
                primaryBeanClasses.add(clazz);
                debugBean("BEAN_PRIMARY class=%s source=ANNOTATION:class", clazz.getName());
            }
        }

        private void registerBeanMethods(Class<?> clazz) {
            if (clazz == null) {
                return;
            }
            for (Method method : clazz.getDeclaredMethods()) {
                Annotation beanAnnotation = findAnnotation(method.getAnnotations(), "Bean");
                if (beanAnnotation == null) {
                    continue;
                }
                Class<?> beanType = method.getReturnType();
                if (beanType == null || beanType == Void.TYPE) {
                    continue;
                }
                List<String> beanNames = extractBeanMethodNames(beanAnnotation, method.getName());
                if (beanNames.isEmpty()) {
                    beanNames.add(method.getName());
                }
                for (String beanName : beanNames) {
                    registerBeanName(
                            beanName,
                            beanType,
                            "ANNOTATION:bean-method:" + method.getDeclaringClass().getName() + "#" + method.getName()
                    );
                }
                if (hasAnnotation(method.getAnnotations(), "Primary")) {
                    primaryBeanClasses.add(beanType);
                    debugBean(
                            "BEAN_PRIMARY class=%s source=ANNOTATION:bean-method:%s#%s",
                            beanType.getName(),
                            method.getDeclaringClass().getName(),
                            method.getName()
                    );
                }
            }
        }

        private void loadXmlBeanMetadata() {
            if (options.projectDir == null || !Files.isDirectory(options.projectDir)) {
                return;
            }
            try (Stream<Path> stream = Files.walk(options.projectDir, 10)) {
                List<Path> xmlFiles = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.toString().toLowerCase(Locale.ROOT).endsWith(".xml"))
                        .filter(path -> !isIgnoredXml(path))
                        .collect(Collectors.toList());
                for (Path xmlFile : xmlFiles) {
                    parseBeanXml(xmlFile);
                }
            } catch (IOException error) {
                if (options.debugRuntime) {
                    System.out.println("[RUNTIME_DEBUG] SPRING_XML_SCAN_FAIL error="
                            + error.getClass().getName() + ": " + error.getMessage());
                }
            }
        }

        private boolean isIgnoredXml(Path xmlFile) {
            if (xmlFile == null) {
                return true;
            }
            String normalized = xmlFile.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
            return normalized.contains("log4j")
                    || normalized.contains("logback")
                    || normalized.endsWith("/pom.xml")
                    || normalized.contains("/.m2repo/")
                    || normalized.contains("/.idea/")
                    || normalized.contains("/build/")
                    || normalized.contains("/target/");
        }

        private void parseBeanXml(Path xmlFile) {
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setExpandEntityReferences(false);
                Document document = factory.newDocumentBuilder().parse(xmlFile.toFile());

                NodeList beanNodes = document.getElementsByTagName("bean");
                for (int i = 0; i < beanNodes.getLength(); i++) {
                    Element beanElement = (Element) beanNodes.item(i);
                    if (beanElement == null) {
                        continue;
                    }
                    String className = normalizeTypeName(beanElement.getAttribute("class"));
                    if (isBlank(className)) {
                        continue;
                    }
                    Class<?> beanClass = loadClassByName(className);
                    if (beanClass == null) {
                        continue;
                    }
                    String id = normalizeBeanName(beanElement.getAttribute("id"));
                    if (!isBlank(id)) {
                        registerBeanName(id, beanClass, "XML:" + xmlFile.toAbsolutePath());
                    }
                    String names = beanElement.getAttribute("name");
                    if (!isBlank(names)) {
                        for (String token : names.split("[,;\\s]+")) {
                            registerBeanName(token, beanClass, "XML:" + xmlFile.toAbsolutePath());
                        }
                    }
                }

                NodeList aliasNodes = document.getElementsByTagName("alias");
                for (int i = 0; i < aliasNodes.getLength(); i++) {
                    Element aliasElement = (Element) aliasNodes.item(i);
                    if (aliasElement == null) {
                        continue;
                    }
                    String name = normalizeBeanName(aliasElement.getAttribute("name"));
                    String alias = normalizeBeanName(aliasElement.getAttribute("alias"));
                    if (!isBlank(name) && !isBlank(alias)) {
                        xmlAliasToName.put(alias, name);
                        debugBean("BEAN_ALIAS alias=%s name=%s source=XML:%s", alias, name, xmlFile.toAbsolutePath());
                    }
                }
            } catch (Throwable error) {
                if (options.debugRuntime) {
                    System.out.println("[RUNTIME_DEBUG] SPRING_XML_PARSE_SKIP file=" + xmlFile
                            + " error=" + error.getClass().getName() + ": " + error.getMessage());
                }
            }
        }

        private Class<?> resolveBeanClassByName(String beanName, Class<?> requiredType) {
            String normalized = resolveAlias(normalizeBeanName(beanName));
            if (isBlank(normalized)) {
                return null;
            }
            List<Class<?>> candidates = beanClassesByName.getOrDefault(normalized, Collections.<Class<?>>emptyList());
            debugBean(
                    "BEAN_NAME_LOOKUP bean=%s resolved=%s required=%s candidates=%s",
                    beanName,
                    normalized,
                    requiredType == null ? "null" : requiredType.getName(),
                    describeClassNames(candidates)
            );
            Class<?> matched = chooseAssignableCandidate(candidates, requiredType);
            if (matched != null) {
                debugBean(
                        "BEAN_NAME_LOOKUP_HIT bean=%s resolved=%s required=%s selected=%s",
                        beanName,
                        normalized,
                        requiredType == null ? "null" : requiredType.getName(),
                        matched.getName()
                );
                return matched;
            }
            debugBean(
                    "BEAN_NAME_LOOKUP_MISS bean=%s resolved=%s required=%s",
                    beanName,
                    normalized,
                    requiredType == null ? "null" : requiredType.getName()
            );
            return null;
        }

        private Class<?> chooseAssignableCandidate(List<Class<?>> candidates, Class<?> requiredType) {
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            List<Class<?>> filtered = new ArrayList<Class<?>>();
            for (Class<?> candidate : candidates) {
                if (candidate == null) {
                    continue;
                }
                if (requiredType == null || requiredType.isAssignableFrom(candidate)) {
                    filtered.add(candidate);
                }
            }
            if (filtered.isEmpty()) {
                return null;
            }
            if (filtered.size() == 1) {
                return filtered.get(0);
            }
            List<Class<?>> primary = new ArrayList<Class<?>>();
            for (Class<?> candidate : filtered) {
                if (primaryBeanClasses.contains(candidate)) {
                    primary.add(candidate);
                }
            }
            if (primary.size() == 1) {
                return primary.get(0);
            }
            filtered.sort(Comparator.comparing(Class::getName));
            return filtered.get(0);
        }

        private int scoreBeanCandidate(Class<?> candidate, String fieldName, String preferredBeanName) {
            int score = 0;
            if (candidate == null) {
                return score;
            }
            if (primaryBeanClasses.contains(candidate)) {
                score += 500;
            }
            Set<String> beanNames = beanNamesByClass.getOrDefault(candidate, Collections.<String>emptySet());
            if (!isBlank(preferredBeanName)) {
                if (beanNames.contains(preferredBeanName)) {
                    score += 800;
                }
            }
            if (!isBlank(fieldName)) {
                if (beanNames.contains(fieldName)) {
                    score += 420;
                }
            }
            String candidateSimple = decapitalize(candidate.getSimpleName());
            if (!isBlank(fieldName) && fieldName.equals(candidateSimple)) {
                score += 180;
            }
            if (candidate.getSimpleName().toLowerCase(Locale.ROOT).endsWith("impl")) {
                score += 40;
            }
            if (isComponentClass(candidate)) {
                score += 25;
            }
            return score;
        }

        private InjectionHint resolveInjectionHint(Field field) {
            if (field == null) {
                return InjectionHint.none();
            }
            Annotation[] annotations = field.getAnnotations();
            String preferredBeanName = "";
            String qualifier = "";
            boolean explicitInjection = false;

            Annotation resource = findAnnotation(annotations, "Resource");
            if (resource != null) {
                explicitInjection = true;
                preferredBeanName = readAnnotationString(resource, "name");
                if (isBlank(preferredBeanName)) {
                    preferredBeanName = readAnnotationString(resource, "value");
                }
                if (isBlank(preferredBeanName)) {
                    preferredBeanName = field.getName();
                }
            }

            Annotation qualifierAnnotation = findAnnotation(annotations, "Qualifier");
            if (qualifierAnnotation != null) {
                qualifier = readAnnotationString(qualifierAnnotation, "value");
            }
            if (isBlank(qualifier)) {
                Annotation named = findAnnotation(annotations, "Named");
                if (named != null) {
                    qualifier = readAnnotationString(named, "value");
                }
            }

            if (hasAnnotation(annotations, "Autowired") || hasAnnotation(annotations, "Inject")) {
                explicitInjection = true;
            }

            return new InjectionHint(
                    normalizeBeanName(preferredBeanName),
                    normalizeBeanName(qualifier),
                    explicitInjection
            );
        }

        private Constructor<?> findAnnotatedConstructor(List<Constructor<?>> constructors, String annotationSimpleName) {
            if (constructors == null || constructors.isEmpty()) {
                return null;
            }
            for (Constructor<?> constructor : constructors) {
                if (constructor == null) {
                    continue;
                }
                if (hasAnnotation(constructor.getAnnotations(), annotationSimpleName)) {
                    return constructor;
                }
            }
            return null;
        }

        private boolean isComponentClass(Class<?> clazz) {
            if (clazz == null) {
                return false;
            }
            for (Annotation annotation : clazz.getAnnotations()) {
                if (annotation == null) {
                    continue;
                }
                String simple = annotation.annotationType().getSimpleName();
                if (COMPONENT_ANNOTATION_NAMES.contains(simple)) {
                    return true;
                }
            }
            return false;
        }

        private String extractComponentName(Class<?> clazz) {
            if (clazz == null) {
                return "";
            }
            for (Annotation annotation : clazz.getAnnotations()) {
                if (annotation == null) {
                    continue;
                }
                String simple = annotation.annotationType().getSimpleName();
                if (!COMPONENT_ANNOTATION_NAMES.contains(simple)) {
                    continue;
                }
                String value = readAnnotationString(annotation, "value");
                if (!isBlank(value)) {
                    return value;
                }
            }
            return "";
        }

        private List<String> extractBeanMethodNames(Annotation beanAnnotation, String defaultMethodName) {
            List<String> names = new ArrayList<String>();
            if (beanAnnotation == null) {
                return names;
            }
            Object value = readAnnotationAttribute(beanAnnotation, "name");
            if (value instanceof String[]) {
                String[] arr = (String[]) value;
                for (String item : arr) {
                    String normalized = normalizeBeanName(item);
                    if (!isBlank(normalized)) {
                        names.add(normalized);
                    }
                }
            }
            if (names.isEmpty()) {
                Object v = readAnnotationAttribute(beanAnnotation, "value");
                if (v instanceof String[]) {
                    String[] arr = (String[]) v;
                    for (String item : arr) {
                        String normalized = normalizeBeanName(item);
                        if (!isBlank(normalized)) {
                            names.add(normalized);
                        }
                    }
                }
            }
            if (names.isEmpty() && !isBlank(defaultMethodName)) {
                names.add(normalizeBeanName(defaultMethodName));
            }
            return names;
        }

        private void registerBeanName(String beanName, Class<?> beanClass, String source) {
            String normalized = normalizeBeanName(beanName);
            if (isBlank(normalized) || beanClass == null) {
                return;
            }
            List<Class<?>> classes = beanClassesByName.computeIfAbsent(normalized, k -> new ArrayList<Class<?>>());
            if (!classes.contains(beanClass)) {
                classes.add(beanClass);
            }
            Set<String> names = beanNamesByClass.computeIfAbsent(beanClass, k -> new LinkedHashSet<String>());
            names.add(normalized);
            if (!isBlank(source)) {
                beanNameSources.computeIfAbsent(normalized, k -> new LinkedHashSet<String>()).add(source);
            }
            debugBean(
                    "BEAN_META_REGISTER name=%s class=%s source=%s",
                    normalized,
                    beanClass.getName(),
                    isBlank(source) ? "unknown" : source
            );
        }

        private String resolveAlias(String beanName) {
            if (isBlank(beanName)) {
                return "";
            }
            String current = beanName;
            Set<String> visiting = new LinkedHashSet<String>();
            while (xmlAliasToName.containsKey(current) && visiting.add(current)) {
                current = xmlAliasToName.get(current);
            }
            return current;
        }

        private void logBeanRegistrySnapshot() {
            if (!options.debugRuntime) {
                return;
            }
            List<String> names = new ArrayList<String>(beanClassesByName.keySet());
            Collections.sort(names);
            debugBean("BEAN_META_SNAPSHOT totalNames=%d", names.size());
            int printed = 0;
            for (String name : names) {
                if (printed >= 250) {
                    debugBean("BEAN_META_SNAPSHOT_MORE remaining=%d", names.size() - printed);
                    break;
                }
                List<Class<?>> classes = beanClassesByName.getOrDefault(name, Collections.<Class<?>>emptyList());
                Set<String> sources = beanNameSources.getOrDefault(name, Collections.<String>emptySet());
                debugBean(
                        "BEAN_META name=%s classes=%s sources=%s",
                        name,
                        describeClassNames(classes),
                        new ArrayList<String>(sources)
                );
                printed++;
            }
        }

        private String describeClassNames(List<Class<?>> classes) {
            if (classes == null || classes.isEmpty()) {
                return "[]";
            }
            List<String> names = new ArrayList<String>();
            for (Class<?> clazz : classes) {
                if (clazz != null) {
                    names.add(clazz.getName());
                }
            }
            Collections.sort(names);
            return names.toString();
        }

        private static String normalizeBeanName(String value) {
            if (value == null) {
                return "";
            }
            String normalized = value.trim();
            if (normalized.isEmpty()) {
                return "";
            }
            if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() > 1) {
                normalized = normalized.substring(1, normalized.length() - 1).trim();
            }
            return normalized;
        }

        private static String normalizeTypeName(String value) {
            if (value == null) {
                return "";
            }
            String normalized = value.trim();
            if (normalized.isEmpty()) {
                return "";
            }
            return normalized;
        }

        private static boolean hasAnnotation(Annotation[] annotations, String simpleName) {
            return findAnnotation(annotations, simpleName) != null;
        }

        private static Annotation findAnnotation(Annotation[] annotations, String simpleName) {
            if (annotations == null || annotations.length == 0 || isBlank(simpleName)) {
                return null;
            }
            for (Annotation annotation : annotations) {
                if (annotation == null) {
                    continue;
                }
                if (simpleName.equals(annotation.annotationType().getSimpleName())) {
                    return annotation;
                }
            }
            return null;
        }

        private static String readAnnotationString(Annotation annotation, String attributeName) {
            Object value = readAnnotationAttribute(annotation, attributeName);
            if (value == null) {
                return "";
            }
            if (value instanceof String) {
                return (String) value;
            }
            return String.valueOf(value);
        }

        private static Object readAnnotationAttribute(Annotation annotation, String attributeName) {
            if (annotation == null || isBlank(attributeName)) {
                return null;
            }
            try {
                Method method = annotation.annotationType().getMethod(attributeName);
                method.setAccessible(true);
                return method.invoke(annotation);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static class InjectionHint {
            public final String preferredBeanName;
            public final String qualifier;
            public final boolean explicitInjection;

            private InjectionHint(String preferredBeanName, String qualifier, boolean explicitInjection) {
                this.preferredBeanName = preferredBeanName;
                this.qualifier = qualifier;
                this.explicitInjection = explicitInjection;
            }

            private static InjectionHint none() {
                return new InjectionHint("", "", false);
            }
        }

        private static List<String> scanClassNames(List<Path> classRoots,
                                                   boolean debugRuntime) throws IOException {
            LinkedHashSet<String> classes = new LinkedHashSet<String>();
            for (Path root : classRoots) {
                if (!Files.exists(root)) {
                    if (debugRuntime) {
                        System.out.println("[RUNTIME_DEBUG] SCAN_SKIP_MISSING_ROOT " + root);
                    }
                    continue;
                }
                try (Stream<Path> stream = Files.walk(root)) {
                    List<Path> classFiles = stream
                            .filter(Files::isRegularFile)
                            .filter(p -> p.toString().endsWith(".class"))
                            .collect(Collectors.toList());
                    for (Path classFile : classFiles) {
                        String fqcn = toClassName(root, classFile);
                        if (fqcn.contains("$")) {
                            continue;
                        }
                        classes.add(fqcn);
                    }
                }
            }
            List<String> sorted = new ArrayList<String>(classes);
            Collections.sort(sorted);
            return sorted;
        }
    }

    public static class CliOptions {
        public final Path projectDir;
        public final List<Path> classesRoots;
        public final List<Path> classpathEntries;
        public final String entryClass;
        public final String entryMethod;
        public final List<String> arguments;
        public final List<String> tracePrefixes;
        public final Path outputDir;
        public final int maxCalls;
        public final int maxRuntimeEvents;
        public final int maxRuntimeObjects;
        public final boolean debugRuntime;
        public final boolean useSpringContext;
        public final boolean softFail;
        public final int softFailMaxSuppressions;
        public final List<String> softFailExceptionPrefixes;
        public final List<String> softFailMethodTokens;

        public CliOptions(Path projectDir,
                          List<Path> classesRoots,
                          List<Path> classpathEntries,
                          String entryClass,
                          String entryMethod,
                          List<String> arguments,
                          List<String> tracePrefixes,
                          Path outputDir,
                          int maxCalls,
                          int maxRuntimeEvents,
                          int maxRuntimeObjects,
                          boolean debugRuntime,
                          boolean useSpringContext,
                          boolean softFail,
                          int softFailMaxSuppressions,
                          List<String> softFailExceptionPrefixes,
                          List<String> softFailMethodTokens) {
            this.projectDir = projectDir;
            this.classesRoots = classesRoots;
            this.classpathEntries = classpathEntries;
            this.entryClass = entryClass;
            this.entryMethod = entryMethod;
            this.arguments = arguments;
            this.tracePrefixes = tracePrefixes;
            this.outputDir = outputDir;
            this.maxCalls = maxCalls;
            this.maxRuntimeEvents = maxRuntimeEvents;
            this.maxRuntimeObjects = maxRuntimeObjects;
            this.debugRuntime = debugRuntime;
            this.useSpringContext = useSpringContext;
            this.softFail = softFail;
            this.softFailMaxSuppressions = softFailMaxSuppressions;
            this.softFailExceptionPrefixes = softFailExceptionPrefixes == null
                    ? new ArrayList<String>()
                    : softFailExceptionPrefixes;
            this.softFailMethodTokens = softFailMethodTokens == null
                    ? new ArrayList<String>()
                    : softFailMethodTokens;
        }

        public static CliOptions parse(String[] args) {
            Path projectDir = null;
            List<Path> classesRoots = new ArrayList<Path>();
            List<Path> classpathEntries = new ArrayList<Path>();
            String entryClass = null;
            String entryMethod = null;
            List<String> arguments = new ArrayList<String>();
            List<String> tracePrefixes = new ArrayList<String>();
            Path outputDir = Paths.get(".").toAbsolutePath().normalize().resolve("build/reports/runtime-sandbox");
            int maxCalls = 200000;
            int maxRuntimeEvents = 60000;
            int maxRuntimeObjects = 20000;
            boolean debugRuntime = false;
            boolean useSpringContext = false;
            boolean softFail = false;
            int softFailMaxSuppressions = 2000;
            List<String> softFailExceptionPrefixes = new ArrayList<String>();
            List<String> softFailMethodTokens = new ArrayList<String>();
            boolean outExplicitlySpecified = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (("--project".equals(arg) || "--project-dir".equals(arg)) && i + 1 < args.length) {
                    projectDir = Paths.get(args[++i]).toAbsolutePath().normalize();
                } else if ("--classes".equals(arg) && i + 1 < args.length) {
                    collectPaths(classesRoots, args[++i]);
                } else if ("--classpath".equals(arg) && i + 1 < args.length) {
                    collectPaths(classpathEntries, args[++i]);
                } else if ("--entry-class".equals(arg) && i + 1 < args.length) {
                    entryClass = args[++i];
                } else if ("--entry-method".equals(arg) && i + 1 < args.length) {
                    entryMethod = args[++i];
                } else if ("--arg".equals(arg) && i + 1 < args.length) {
                    arguments.add(args[++i]);
                } else if ("--trace-prefix".equals(arg) && i + 1 < args.length) {
                    collectTokens(tracePrefixes, args[++i]);
                } else if ("--out".equals(arg) && i + 1 < args.length) {
                    outputDir = Paths.get(args[++i]).toAbsolutePath().normalize();
                    outExplicitlySpecified = true;
                } else if ("--max-calls".equals(arg) && i + 1 < args.length) {
                    maxCalls = Integer.parseInt(args[++i]);
                } else if ("--max-runtime-events".equals(arg) && i + 1 < args.length) {
                    maxRuntimeEvents = Integer.parseInt(args[++i]);
                } else if ("--max-runtime-objects".equals(arg) && i + 1 < args.length) {
                    maxRuntimeObjects = Integer.parseInt(args[++i]);
                } else if ("--debug-runtime".equals(arg)) {
                    debugRuntime = true;
                } else if ("--use-spring-context".equals(arg)) {
                    useSpringContext = true;
                } else if ("--soft-fail".equals(arg)) {
                    softFail = true;
                } else if ("--soft-fail-max".equals(arg) && i + 1 < args.length) {
                    softFailMaxSuppressions = Integer.parseInt(args[++i]);
                } else if ("--soft-fail-exception-prefix".equals(arg) && i + 1 < args.length) {
                    collectTokens(softFailExceptionPrefixes, args[++i]);
                } else if ("--soft-fail-method-token".equals(arg) && i + 1 < args.length) {
                    collectTokens(softFailMethodTokens, args[++i]);
                }
            }

            if (entryMethod == null || entryMethod.trim().isEmpty()) {
                throw new IllegalArgumentException("Missing --entry-method");
            }
            EntryMethodInput parsedMethod = EntryMethodInput.parse(entryMethod);
            entryMethod = parsedMethod.methodPart;
            if ((entryClass == null || entryClass.trim().isEmpty()) && parsedMethod.classPart != null) {
                entryClass = parsedMethod.classPart;
            }

            if (projectDir != null) {
                if (!outExplicitlySpecified) {
                    outputDir = projectDir.resolve("build/reports/runtime-sandbox").toAbsolutePath().normalize();
                }
                if (classesRoots.isEmpty()) {
                    classesRoots.addAll(discoverClassesRoots(projectDir));
                }
                if (classpathEntries.isEmpty()) {
                    classpathEntries.addAll(discoverClasspathEntries(projectDir));
                }
            }
            if (classesRoots.isEmpty()) {
                if (projectDir != null) {
                    classesRoots.add(projectDir.resolve("target/classes").toAbsolutePath().normalize());
                } else {
                    classesRoots.add(Paths.get("target/classes").toAbsolutePath().normalize());
                }
            }
            if (entryClass == null || entryClass.trim().isEmpty()) {
                throw new IllegalArgumentException("Missing entry class. Use --entry-class, or pass --entry-method as Class#method[/arity].");
            }
            if (tracePrefixes.isEmpty()) {
                tracePrefixes.add(defaultTracePrefix(entryClass));
            }

            return new CliOptions(
                    projectDir,
                    normalizePaths(classesRoots),
                    normalizePaths(classpathEntries),
                    entryClass,
                    entryMethod,
                    arguments,
                    normalizeTokens(tracePrefixes),
                    outputDir,
                    maxCalls,
                    maxRuntimeEvents,
                    maxRuntimeObjects,
                    debugRuntime,
                    useSpringContext,
                    softFail,
                    softFailMaxSuppressions,
                    normalizeTokens(softFailExceptionPrefixes),
                    normalizeTokens(softFailMethodTokens)
            );
        }

        private static String defaultTracePrefix(String entryClass) {
            int lastDot = entryClass.lastIndexOf('.');
            if (lastDot <= 0) {
                return entryClass;
            }
            return entryClass.substring(0, lastDot);
        }
    }

    private static void collectPaths(List<Path> out, String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        String[] segments = raw.split(File.pathSeparator);
        for (String segment : segments) {
            if (segment == null || segment.trim().isEmpty()) {
                continue;
            }
            String[] csv = segment.split(",");
            for (String token : csv) {
                String value = token.trim();
                if (!value.isEmpty()) {
                    out.add(Paths.get(value));
                }
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String decapitalize(String value) {
        if (isBlank(value)) {
            return "";
        }
        if (value.length() == 1) {
            return value.toLowerCase(Locale.ROOT);
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private static String toClassName(Path root, Path classFile) {
        String relative = root.relativize(classFile).toString();
        String normalized = relative.substring(0, relative.length() - ".class".length());
        return normalized.replace(File.separatorChar, '.');
    }

    private static void collectTokens(List<String> out, String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        String value = raw.trim();
        if ((value.startsWith("[") && value.endsWith("]")) || (value.startsWith("(") && value.endsWith(")"))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        for (String token : value.split("[,;]")) {
            String normalized = token == null ? "" : token.trim();
            if (normalized.length() >= 2
                    && ((normalized.startsWith("\"") && normalized.endsWith("\""))
                    || (normalized.startsWith("'") && normalized.endsWith("'")))) {
                normalized = normalized.substring(1, normalized.length() - 1).trim();
            }
            if (!normalized.isEmpty()) {
                out.add(normalized);
            }
        }
    }

    private static List<Path> normalizePaths(List<Path> raw) {
        List<Path> normalized = new ArrayList<Path>();
        for (Path path : raw) {
            normalized.add(path.toAbsolutePath().normalize());
        }
        return normalized;
    }

    private static List<Path> discoverClassesRoots(Path projectDir) {
        LinkedHashSet<Path> roots = new LinkedHashSet<Path>();
        Path mavenMain = projectDir.resolve("target/classes").toAbsolutePath().normalize();
        if (Files.isDirectory(mavenMain)) {
            roots.add(mavenMain);
        }
        try (Stream<Path> stream = Files.walk(projectDir, 8)) {
            List<Path> detected = stream
                    .filter(Files::isDirectory)
                    .filter(path -> path.toString().endsWith(File.separator + "target" + File.separator + "classes")
                            || path.toString().endsWith(File.separator + "build" + File.separator + "classes" + File.separator + "java" + File.separator + "main"))
                    .collect(Collectors.toList());
            for (Path path : detected) {
                roots.add(path.toAbsolutePath().normalize());
            }
        } catch (IOException ignored) {
            // best effort auto-discovery
        }
        return new ArrayList<Path>(roots);
    }

    private static List<Path> discoverClasspathEntries(Path projectDir) {
        LinkedHashSet<Path> entries = new LinkedHashSet<Path>();
        try (Stream<Path> stream = Files.walk(projectDir, 8)) {
            List<Path> jars = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".jar"))
                    .filter(path -> {
                        String normalized = path.toString().replace('\\', '/');
                        return normalized.contains("/target/") || normalized.contains("/lib/");
                    })
                    .collect(Collectors.toList());
            for (Path jar : jars) {
                entries.add(jar.toAbsolutePath().normalize());
            }
        } catch (IOException ignored) {
            // best effort auto-discovery
        }
        return new ArrayList<Path>(entries);
    }

    private static List<String> normalizeTokens(List<String> raw) {
        List<String> tokens = new ArrayList<String>();
        for (String value : raw) {
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            tokens.add(value.trim());
        }
        return tokens;
    }

    private static boolean isSimpleType(Class<?> type) {
        return type.isPrimitive()
                || type == String.class
                || Number.class.isAssignableFrom(type)
                || type == Boolean.class
                || type == Character.class
                || type.isEnum()
                || type == Class.class;
    }

    private static Object defaultValue(Class<?> type) {
        if (type == void.class || type == Void.class) {
            return null;
        }
        if (!type.isPrimitive()) {
            if (type == String.class) {
                return "";
            }
            if (List.class.isAssignableFrom(type)) {
                return new ArrayList<Object>();
            }
            if (Set.class.isAssignableFrom(type)) {
                return new LinkedHashSet<Object>();
            }
            if (Map.class.isAssignableFrom(type)) {
                return new LinkedHashMap<Object, Object>();
            }
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == double.class) {
            return 0.0d;
        }
        return null;
    }

    public static class InstrumentationStats {
        public final AtomicInteger discovered = new AtomicInteger(0);
        public final AtomicInteger transformed = new AtomicInteger(0);
        public final AtomicInteger ignored = new AtomicInteger(0);
        public final AtomicInteger errors = new AtomicInteger(0);
    }

    public static class EntryMethodInput {
        public final String classPart;
        public final String methodPart;

        public EntryMethodInput(String classPart, String methodPart) {
            this.classPart = classPart;
            this.methodPart = methodPart;
        }

        public static EntryMethodInput parse(String raw) {
            if (raw == null) {
                return new EntryMethodInput(null, "");
            }
            String value = raw.trim();
            if (value.isEmpty()) {
                return new EntryMethodInput(null, "");
            }
            int hash = value.lastIndexOf('#');
            if (hash <= 0 || hash + 1 >= value.length()) {
                return new EntryMethodInput(null, value);
            }
            String classPart = value.substring(0, hash).trim();
            String methodPart = value.substring(hash + 1).trim();
            return new EntryMethodInput(classPart.isEmpty() ? null : classPart, methodPart);
        }
    }
}
