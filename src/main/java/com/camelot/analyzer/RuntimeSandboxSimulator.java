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

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isNative;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;

public class RuntimeSandboxSimulator {
    public static volatile RuntimeTraceCollector ACTIVE_COLLECTOR;
    private static volatile boolean AGENT_INSTALLED = false;
    private static volatile InstrumentationStats LAST_INSTRUMENTATION_STATS;
    public static final AtomicInteger ADVICE_HITS = new AtomicInteger(0);
    public static final AtomicInteger NULL_COLLECTOR_HITS = new AtomicInteger(0);

    public static void main(String[] args) throws Exception {
        CliOptions options = CliOptions.parse(args);
        Files.createDirectories(options.outputDir);

        ProjectClassIndex projectClassIndex = buildProjectClassIndex(options.classesRoots, options.debugRuntime);
        ProjectAwareClassLoader classLoader = buildClassLoader(options, projectClassIndex);
        installTracingAgent(options);

        RuntimeTraceCollector collector = new RuntimeTraceCollector(options.maxCalls);
        ACTIVE_COLLECTOR = collector;

        RuntimeTraceReport report = new RuntimeTraceReport();
        report.generatedAt = Instant.now().toString();
        report.entryClass = options.entryClass;
        report.entryMethod = options.entryMethod;
        report.tracePrefixes = new ArrayList<String>(options.tracePrefixes);
        report.classesRoots = toStringList(options.classesRoots);
        report.classpathEntries = toStringList(options.classpathEntries);
        report.arguments = new ArrayList<String>(options.arguments);
        report.discoveredProjectClassCount = projectClassIndex.classNames.size();

        long startedAt = System.currentTimeMillis();
        SandboxBeanFactory beanFactory = null;
        try {
            if (options.debugRuntime) {
                printRuntimeStartDebug(options, classLoader, projectClassIndex);
            }
            beanFactory = new SandboxBeanFactory(classLoader, options, projectClassIndex);
            Class<?> entryClass = Class.forName(options.entryClass, true, classLoader);
            Method entryMethod = resolveEntryMethod(entryClass, options.entryMethod, options.arguments.size());
            Object entryBean = beanFactory.getBean(entryClass);
            Object[] invokeArgs = buildInvokeArgs(entryMethod, options.arguments, beanFactory, options.debugRuntime);
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
            ACTIVE_COLLECTOR = null;
            classLoader.close();
        }

        Path jsonPath = options.outputDir.resolve("runtime-trace.json");
        Path treePath = options.outputDir.resolve("runtime-trace-tree.txt");
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(jsonPath.toFile(), report);
        Files.write(treePath, buildTraceTree(report).getBytes(StandardCharsets.UTF_8));

        System.out.println("Runtime sandbox finished.");
        System.out.println("Trace JSON: " + jsonPath.toAbsolutePath());
        System.out.println("Trace tree: " + treePath.toAbsolutePath());
        System.out.println("Calls:      " + report.callCount);
        System.out.println("Edges:      " + report.edges.size());
        System.out.println("AdviceHits: " + ADVICE_HITS.get());
        System.out.println("NullCollect:" + NULL_COLLECTOR_HITS.get());
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

    private static synchronized void installTracingAgent(CliOptions options) {
        if (AGENT_INSTALLED) {
            return;
        }
        ByteBuddyAgent.install();
        final InstrumentationStats stats = new InstrumentationStats();

        ElementMatcher.Junction<TypeDescription> typeMatcher = ElementMatchers.none();
        for (String prefix : options.tracePrefixes) {
            typeMatcher = typeMatcher.or(nameStartsWith(prefix));
        }

        AgentBuilder.Listener listener = new AgentBuilder.Listener() {
            @Override
            public void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
                if (matchesAnyPrefix(typeName, options.tracePrefixes)) {
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
                if (matchesAnyPrefix(typeName, options.tracePrefixes)) {
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
                if (matchesAnyPrefix(typeName, options.tracePrefixes)) {
                    stats.ignored.incrementAndGet();
                    if (options.debugRuntime) {
                        System.out.println("[RUNTIME_DEBUG] IGNORED " + typeName + " loaded=" + loaded + " cl=" + classLoader);
                    }
                }
            }

            @Override
            public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
                if (matchesAnyPrefix(typeName, options.tracePrefixes)) {
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

    private static Object[] buildInvokeArgs(Method method,
                                            List<String> rawArgs,
                                            SandboxBeanFactory beanFactory,
                                            boolean debugRuntime) {
        Class<?>[] types = method.getParameterTypes();
        if (rawArgs.size() > types.length) {
            throw new IllegalArgumentException("Argument count mismatch. required<=" + types.length + " provided=" + rawArgs.size());
        }
        Object[] values = new Object[types.length];
        AutoArgumentGenerator generator = new AutoArgumentGenerator(beanFactory, debugRuntime);
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
        private final SandboxBeanFactory beanFactory;
        private final boolean debugRuntime;
        private final Map<Class<?>, Object> cache = new LinkedHashMap<Class<?>, Object>();

        private AutoArgumentGenerator(SandboxBeanFactory beanFactory, boolean debugRuntime) {
            this.beanFactory = beanFactory;
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
            if (beanFactory != null) {
                try {
                    return beanFactory.getBean(targetType);
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
            if (beanFactory != null) {
                try {
                    return beanFactory.getBean(targetType);
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

    public static class TraceAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static String onEnter(@Advice.Origin("#t") String typeName,
                                     @Advice.Origin("#m") String methodName) {
            ADVICE_HITS.incrementAndGet();
            RuntimeTraceCollector collector = ACTIVE_COLLECTOR;
            String methodId = (typeName == null ? "unknown" : typeName)
                    + "#"
                    + (methodName == null ? "unknown" : methodName);
            if (collector == null || methodId == null) {
                NULL_COLLECTOR_HITS.incrementAndGet();
                return null;
            }
            collector.onEnter(methodId);
            return methodId;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Enter String methodId) {
            RuntimeTraceCollector collector = ACTIVE_COLLECTOR;
            if (collector == null || methodId == null) {
                return;
            }
            collector.onExit(methodId);
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
            if (callCount.incrementAndGet() > maxCalls) {
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

    public static class SandboxBeanFactory {
        private final ClassLoader classLoader;
        private final CliOptions options;
        private final List<String> discoveredClassNames;
        private final Map<String, Class<?>> loadedByClassName = new LinkedHashMap<String, Class<?>>();
        private final Set<String> failedClassNames = new LinkedHashSet<String>();
        private final Map<Class<?>, Object> singletonByConcreteClass = new LinkedHashMap<Class<?>, Object>();
        private final Set<Class<?>> creating = new LinkedHashSet<Class<?>>();
        private int classLoadFailLogs = 0;
        private static final int MAX_CLASS_LOAD_FAIL_LOGS = 60;

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
        }

        public int getScannedClassCount() {
            return discoveredClassNames.size();
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

        public Object getBean(Class<?> requestedType) {
            Class<?> targetClass = resolveImplementationClass(requestedType);
            if (targetClass == null) {
                if (requestedType.isInterface()) {
                    return createInterfaceMock(requestedType);
                }
                throw new IllegalStateException("No implementation for type: " + requestedType.getName());
            }
            Object existing = singletonByConcreteClass.get(targetClass);
            if (existing != null) {
                return existing;
            }
            if (!creating.add(targetClass)) {
                return singletonByConcreteClass.get(targetClass);
            }
            try {
                Object instance = instantiate(targetClass);
                singletonByConcreteClass.put(targetClass, instance);
                injectFields(instance, targetClass);
                return instance;
            } catch (Exception e) {
                throw new RuntimeException("Create bean failed: " + targetClass.getName(), e);
            } finally {
                creating.remove(targetClass);
            }
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
                    Object dependency = resolveDependency(field.getType());
                    if (dependency != null) {
                        field.set(instance, dependency);
                    }
                }
                current = current.getSuperclass();
            }
        }

        private Object resolveDependency(Class<?> dependencyType) {
            if (dependencyType.isInterface()) {
                Class<?> impl = resolveImplementationClass(dependencyType);
                if (impl == null) {
                    return createInterfaceMock(dependencyType);
                }
                return getBean(impl);
            }

            if (Modifier.isAbstract(dependencyType.getModifiers())) {
                Class<?> impl = resolveImplementationClass(dependencyType);
                if (impl == null) {
                    return null;
                }
                return getBean(impl);
            }
            return getBean(dependencyType);
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
            for (Constructor<?> constructor : constructors) {
                Object[] args = new Object[constructor.getParameterCount()];
                boolean failed = false;
                for (int i = 0; i < constructor.getParameterCount(); i++) {
                    Class<?> parameterType = constructor.getParameterTypes()[i];
                    if (isSimpleType(parameterType)) {
                        failed = true;
                        break;
                    }
                    Object dep = resolveDependency(parameterType);
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

        private Class<?> resolveImplementationClass(Class<?> requestedType) {
            if (!requestedType.isInterface() && !Modifier.isAbstract(requestedType.getModifiers())) {
                return requestedType;
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
                return null;
            }
            candidates.sort(Comparator.comparing(Class::getName));
            for (Class<?> candidate : candidates) {
                String lower = candidate.getSimpleName().toLowerCase(Locale.ROOT);
                if (lower.endsWith("impl")) {
                    return candidate;
                }
            }
            return candidates.get(0);
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
        public final boolean debugRuntime;

        public CliOptions(Path projectDir,
                          List<Path> classesRoots,
                          List<Path> classpathEntries,
                          String entryClass,
                          String entryMethod,
                          List<String> arguments,
                          List<String> tracePrefixes,
                          Path outputDir,
                          int maxCalls,
                          boolean debugRuntime) {
            this.projectDir = projectDir;
            this.classesRoots = classesRoots;
            this.classpathEntries = classpathEntries;
            this.entryClass = entryClass;
            this.entryMethod = entryMethod;
            this.arguments = arguments;
            this.tracePrefixes = tracePrefixes;
            this.outputDir = outputDir;
            this.maxCalls = maxCalls;
            this.debugRuntime = debugRuntime;
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
            boolean debugRuntime = false;
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
                } else if ("--debug-runtime".equals(arg)) {
                    debugRuntime = true;
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
                classesRoots.add(Paths.get("target/classes").toAbsolutePath().normalize());
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
                    debugRuntime
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
