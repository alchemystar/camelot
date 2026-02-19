package com.camelot.runtime.bootstrap;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.IdentityHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SpringBootNativeLauncher {

    private static final Logger LOG = LoggerFactory.getLogger(SpringBootNativeLauncher.class);
    private static final String SPRING_BOOT_BUILDER_CLASS = "org.springframework.boot.builder.SpringApplicationBuilder";
    private static final Set<String> SCAN_ANNOTATION_NAMES = new HashSet<String>(Arrays.asList(
            "org.springframework.context.annotation.ComponentScan",
            "org.springframework.boot.autoconfigure.SpringBootApplication",
            "org.mybatis.spring.annotation.MapperScan"
    ));
    private static final List<Pattern> FAILED_BEAN_PATTERNS = Arrays.asList(
            Pattern.compile("Error creating bean with name '([^']+)'"),
            Pattern.compile("Error creating bean with name \"([^\"]+)\""),
            Pattern.compile("bean named '([^']+)'"),
            Pattern.compile("bean named \"([^\"]+)\""),
            Pattern.compile("No qualifying bean named '([^']+)'")
    );
    private static final List<Pattern> FAILED_TYPE_PATTERNS = Arrays.asList(
            Pattern.compile("Failed to instantiate \\[([^\\]]+)\\]"),
            Pattern.compile("Failed to introspect Class \\[([^\\]]+)\\]"),
            Pattern.compile("Could not resolve matching constructor on bean class \\[([^\\]]+)\\]"),
            Pattern.compile("Lookup method resolution failed; nested exception is java\\.lang\\.IllegalStateException: Failed to introspect Class \\[([^\\]]+)\\]")
    );
    private static final List<Pattern> EXPECTED_TYPE_PATTERNS = Arrays.asList(
            Pattern.compile("expected to be of type '([^']+)'"),
            Pattern.compile("expected to be of type \"([^\"]+)\""),
            Pattern.compile("Required type: ([A-Za-z0-9_.$]+)")
    );
    private static final List<Pattern> MISSING_BEAN_TYPE_PATTERNS = Arrays.asList(
            Pattern.compile("No qualifying bean of type '([^']+)'"),
            Pattern.compile("No qualifying bean of type \"([^\"]+)\""),
            Pattern.compile("required a bean of type '([^']+)'"),
            Pattern.compile("required a bean of type \"([^\"]+)\"")
    );
    private static final List<Pattern> ACTUAL_TYPE_PATTERNS = Arrays.asList(
            Pattern.compile("but was actually of type '([^']+)'"),
            Pattern.compile("but was actually of type \"([^\"]+)\""),
            Pattern.compile("actual type ([A-Za-z0-9_.$]+)")
    );
    private static final List<String> BUILTIN_FORCE_MOCK_CLASS_PREFIXES = Arrays.asList(
            ".*ThriftClientProxy.*",
            ".*pay.mra.*",
            "com.taobao.*"
    );
    public StartResult start(StartRequest request) {
        validate(request);

        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader classLoader = resolveExecutionClassLoader(request, originalClassLoader);
        Thread.currentThread().setContextClassLoader(classLoader);
        try {
            Class<?> startupClass = loadClass(request.getStartupClassName(), classLoader);
            List<String> packagePrefixes = resolveMockScopePackages(request, startupClass);

            List<String> activeProfiles = request.getActiveProfiles().isEmpty()
                    ? Collections.singletonList("test")
                    : request.getActiveProfiles();
            Map<String, String> launchProperties = mergeLaunchProperties(request.getExtraProperties());
            List<String> forceMockClassPrefixes = new ArrayList<String>(request.getForceMockClassPrefixes());
            appendIfAbsent(forceMockClassPrefixes, BUILTIN_FORCE_MOCK_CLASS_PREFIXES);
            LinkedHashSet<String> forceMockBeanNames = new LinkedHashSet<String>();
            LinkedHashMap<String, String> forceMockBeanTargetTypes = new LinkedHashMap<String, String>();
            LinkedHashSet<String> forceMockMissingTypeNames = new LinkedHashSet<String>();
            boolean servletFallbackApplied = false;
            boolean suppressSpringApplicationCallbacks = false;
            RunOutcome outcome = null;
            IllegalStateException lastError = null;
            int attempt = 0;
            while (true) {
                attempt++;
                try {
                    outcome = runApplication(
                            startupClass,
                            classLoader,
                            activeProfiles,
                            launchProperties,
                            packagePrefixes,
                            forceMockClassPrefixes,
                            forceMockBeanNames,
                            forceMockBeanTargetTypes,
                            forceMockMissingTypeNames,
                            suppressSpringApplicationCallbacks,
                            request
                    );
                    lastError = null;
                    break;
                } catch (IllegalStateException runError) {
                    lastError = runError;
                    LOG.warn("Spring startup attempt {} failed, apply mock fallback and retry.", Integer.valueOf(attempt));
                    if (!servletFallbackApplied && isMissingServletEnvironmentClass(runError)) {
                        LinkedHashMap<String, String> fallbackProperties = new LinkedHashMap<String, String>(launchProperties);
                        fallbackProperties.put("spring.main.web-application-type", "none");
                        launchProperties = fallbackProperties;
                        servletFallbackApplied = true;
                        LOG.warn("Detected missing servlet environment class, fallback to non-web mode and retry.");
                        continue;
                    }
                    if (!suppressSpringApplicationCallbacks && isPrepareContextFailure(runError)) {
                        suppressSpringApplicationCallbacks = true;
                        LOG.warn("Detected failure during SpringApplication.prepareContext; suppress callbacks and retry.");
                        continue;
                    }
                    String failedBeanName = extractFailedBeanName(runError);
                    String expectedTypeName = extractExpectedTypeName(runError);
                    String actualTypeName = extractActualTypeName(runError);
                    String missingBeanTypeName = extractMissingBeanTypeName(runError);
                    String beanFailureReason = buildBeanFailureReason(runError, failedBeanName, expectedTypeName, actualTypeName);
                    if (!isBlank(failedBeanName) && !isBlank(expectedTypeName)) {
                        String normalizedBeanName = failedBeanName.trim();
                        String normalizedExpectedType = expectedTypeName.trim();
                        boolean beanAdded = forceMockBeanNames.add(normalizedBeanName);
                        if (isUsableForcedTargetType(normalizedExpectedType)) {
                            String previousExpectedType = forceMockBeanTargetTypes.get(normalizedBeanName);
                            boolean expectedTypeChanged = !normalizedExpectedType.equals(previousExpectedType);
                            if (expectedTypeChanged) {
                                forceMockBeanTargetTypes.put(normalizedBeanName, normalizedExpectedType);
                            }
                            if (expectedTypeChanged || beanAdded) {
                                LOG.warn(
                                        "Spring startup type mismatch on bean '{}', force-mock expected type '{}' and retry. reason={}",
                                        normalizedBeanName,
                                        normalizedExpectedType,
                                        beanFailureReason
                                );
                                continue;
                            }
                        } else if (beanAdded) {
                            LOG.warn(
                                    "Spring startup failed on bean '{}', expected type '{}' is unsupported; force-mock by bean name only and retry. reason={}",
                                    normalizedBeanName,
                                    normalizedExpectedType,
                                    beanFailureReason
                            );
                            continue;
                        }
                    }
                    if (isUsableForcedTargetType(missingBeanTypeName)) {
                        String normalizedMissingType = missingBeanTypeName.trim();
                        if (forceMockMissingTypeNames.add(normalizedMissingType)) {
                            LOG.warn(
                                    "Spring startup missing bean type '{}', pre-register no-op mock and retry. reason={}",
                                    normalizedMissingType,
                                    beanFailureReason
                            );
                            continue;
                        }
                    }
                    if (!isBlank(failedBeanName)) {
                        String normalizedBeanName = failedBeanName.trim();
                        if (forceMockBeanNames.add(normalizedBeanName)) {
                            LOG.warn(
                                    "Spring startup failed on bean '{}', force-mock and retry. reason={}",
                                    normalizedBeanName,
                                    beanFailureReason
                            );
                            continue;
                        }
                    }
                    LOG.error("Spring startup failed and no further fallback can be applied. diagnostics:\n{}",
                            buildFailureDiagnostics(runError));
                    break;
                }
            }
            if (lastError != null) {
                throw lastError;
            }
            if (outcome == null) {
                throw new IllegalStateException("Spring context start failed without explicit error details");
            }
            Object context = outcome.contextObject;
            if (context == null) {
                throw new IllegalStateException("Spring context start failed: run(...) returned null");
            }
            return new StartResult(
                    context,
                    startupClass.getName(),
                    new ArrayList<String>(activeProfiles),
                    outcome.mockedBeanTypes,
                    launchProperties
            );
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    private static RunOutcome runApplication(Class<?> startupClass,
                                             ClassLoader classLoader,
                                             List<String> activeProfiles,
                                             Map<String, String> launchProperties,
                                             List<String> packagePrefixes,
                                             List<String> forceMockClassPrefixes,
                                             Set<String> forceMockBeanNames,
                                             Map<String, String> forceMockBeanTargetTypes,
                                             Set<String> forceMockMissingTypeNames,
                                             boolean suppressSpringApplicationCallbacks,
                                             StartRequest request) {
        List<String> propertyArgs = mapToKeyValueArgs(launchProperties);
        InitializerHandle initializerHandle = createMockingInitializerHandle(
                classLoader,
                packagePrefixes,
                forceMockClassPrefixes,
                forceMockBeanNames,
                forceMockBeanTargetTypes,
                forceMockMissingTypeNames
        );
        Object builder = createBuilderInstance(startupClass, classLoader);
        builder = invokeBuilder(builder, "profiles", new Class[]{String[].class}, new Object[]{activeProfiles.toArray(new String[0])});
        builder = invokeBuilder(
                builder,
                "initializers",
                new Class[]{initializerHandle.initializerArrayType},
                new Object[]{initializerHandle.initializerArray}
        );
        suppressSpringApplicationListeners(builder);
        if (suppressSpringApplicationCallbacks) {
            suppressSpringApplicationCallbacks(builder);
        }
        builder = invokeBuilder(builder, "properties", new Class[]{String[].class}, new Object[]{propertyArgs.toArray(new String[0])});
        Object contextObject = invokeBuilder(
                builder,
                "run",
                new Class[]{String[].class},
                new Object[]{request.getApplicationArgs().toArray(new String[0])}
        );
        return new RunOutcome(contextObject, initializerHandle.snapshotMockedBeanTypes());
    }

    private static String extractFailedBeanName(Throwable error) {
        Throwable cursor = error;
        String lastBeanName = null;
        while (cursor != null) {
            String beanNameFromType = extractBeanNameFromExceptionType(cursor);
            if (!isBlank(beanNameFromType)) {
                lastBeanName = beanNameFromType;
            }
            String message = cursor.getMessage();
            if (!isBlank(message)) {
                for (Pattern pattern : FAILED_BEAN_PATTERNS) {
                    Matcher matcher = pattern.matcher(message);
                    if (matcher.find()) {
                        String candidate = matcher.group(1);
                        if (!isBlank(candidate)) {
                            lastBeanName = candidate;
                        }
                    }
                }
            }
            cursor = cursor.getCause();
        }
        return lastBeanName;
    }

    private static String extractBeanNameFromExceptionType(Throwable error) {
        if (error == null) {
            return null;
        }
        try {
            Method method = error.getClass().getMethod("getBeanName");
            Object result = method.invoke(error);
            return result == null ? null : String.valueOf(result);
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String extractExpectedTypeName(Throwable error) {
        Throwable cursor = error;
        String lastTypeName = null;
        while (cursor != null) {
            String requiredTypeFromException = extractRequiredTypeFromExceptionType(cursor);
            if (!isBlank(requiredTypeFromException)) {
                lastTypeName = normalizeTypeName(requiredTypeFromException);
            }
            String message = cursor.getMessage();
            if (!isBlank(message)) {
                for (Pattern pattern : EXPECTED_TYPE_PATTERNS) {
                    Matcher matcher = pattern.matcher(message);
                    if (matcher.find()) {
                        String candidate = normalizeTypeName(matcher.group(1));
                        if (!isBlank(candidate)) {
                            lastTypeName = candidate;
                        }
                    }
                }
            }
            cursor = cursor.getCause();
        }
        return lastTypeName;
    }

    private static String extractMissingBeanTypeName(Throwable error) {
        Throwable cursor = error;
        String lastTypeName = null;
        while (cursor != null) {
            String beanTypeFromException = extractMissingBeanTypeFromExceptionType(cursor);
            if (!isBlank(beanTypeFromException)) {
                lastTypeName = normalizeTypeName(beanTypeFromException);
            }
            String message = cursor.getMessage();
            if (!isBlank(message)) {
                for (Pattern pattern : MISSING_BEAN_TYPE_PATTERNS) {
                    Matcher matcher = pattern.matcher(message);
                    if (matcher.find()) {
                        String candidate = normalizeTypeName(matcher.group(1));
                        if (!isBlank(candidate)) {
                            lastTypeName = candidate;
                        }
                    }
                }
            }
            cursor = cursor.getCause();
        }
        return lastTypeName;
    }

    private static String extractActualTypeName(Throwable error) {
        Throwable cursor = error;
        String lastTypeName = null;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (!isBlank(message)) {
                for (Pattern pattern : ACTUAL_TYPE_PATTERNS) {
                    Matcher matcher = pattern.matcher(message);
                    if (matcher.find()) {
                        String candidate = normalizeTypeName(matcher.group(1));
                        if (!isBlank(candidate)) {
                            lastTypeName = candidate;
                        }
                    }
                }
            }
            cursor = cursor.getCause();
        }
        return lastTypeName;
    }

    private static String extractRequiredTypeFromExceptionType(Throwable error) {
        if (error == null) {
            return null;
        }
        try {
            Method method = error.getClass().getMethod("getRequiredType");
            Object result = method.invoke(error);
            if (result instanceof Class<?>) {
                return ((Class<?>) result).getName();
            }
            return result == null ? null : String.valueOf(result);
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String extractMissingBeanTypeFromExceptionType(Throwable error) {
        if (error == null) {
            return null;
        }
        try {
            Method method = error.getClass().getMethod("getBeanType");
            Object result = method.invoke(error);
            if (result instanceof Class<?>) {
                return ((Class<?>) result).getName();
            }
            return result == null ? null : String.valueOf(result);
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalizeTypeName(String rawTypeName) {
        if (isBlank(rawTypeName)) {
            return null;
        }
        String normalized = rawTypeName.trim();
        if (normalized.startsWith("class ")) {
            normalized = normalized.substring("class ".length()).trim();
        } else if (normalized.startsWith("interface ")) {
            normalized = normalized.substring("interface ".length()).trim();
        }
        return isBlank(normalized) ? null : normalized;
    }

    private static boolean isPrepareContextFailure(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            StackTraceElement[] stack = cursor.getStackTrace();
            if (stack != null) {
                for (StackTraceElement element : stack) {
                    if (element == null) {
                        continue;
                    }
                    if ("org.springframework.boot.SpringApplication".equals(element.getClassName())
                            && "prepareContext".equals(element.getMethodName())) {
                        return true;
                    }
                }
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    private static boolean isUsableForcedTargetType(String typeName) {
        if (isBlank(typeName)) {
            return false;
        }
        String clean = typeName.trim();
        if ("java.lang.Object".equals(clean)) {
            return false;
        }
        if (clean.startsWith("org.springframework.beans.factory.")
                || clean.startsWith("org.springframework.context.")
                || clean.startsWith("org.springframework.core.")
                || clean.startsWith("org.springframework.boot.")) {
            return false;
        }
        return !"org.springframework.beans.factory.InitializingBean".equals(clean)
                && !"org.springframework.beans.factory.DisposableBean".equals(clean)
                && !"org.springframework.beans.factory.FactoryBean".equals(clean)
                && !"org.springframework.beans.factory.SmartFactoryBean".equals(clean)
                && !"org.springframework.beans.factory.config.BeanPostProcessor".equals(clean)
                && !"org.springframework.beans.factory.config.BeanFactoryPostProcessor".equals(clean)
                && !"org.springframework.context.SmartLifecycle".equals(clean);
    }

    private static String extractFailedTypeName(Throwable error) {
        Throwable cursor = error;
        String lastTypeName = null;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (!isBlank(message)) {
                for (Pattern pattern : FAILED_TYPE_PATTERNS) {
                    Matcher matcher = pattern.matcher(message);
                    if (matcher.find()) {
                        String candidate = matcher.group(1);
                        if (!isBlank(candidate)) {
                            lastTypeName = candidate;
                        }
                    }
                }
            }
            cursor = cursor.getCause();
        }
        return lastTypeName;
    }

    private static boolean addIfAbsent(List<String> target, String candidate) {
        if (target == null || isBlank(candidate)) {
            return false;
        }
        String normalized = candidate.trim();
        for (String existing : target) {
            if (normalized.equals(existing)) {
                return false;
            }
        }
        target.add(normalized);
        return true;
    }

    private static boolean appendIfAbsent(List<String> target, List<String> candidates) {
        if (target == null || candidates == null || candidates.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (String candidate : candidates) {
            changed = addIfAbsent(target, candidate) || changed;
        }
        return changed;
    }

    private static String buildFailureDiagnostics(Throwable error) {
        if (error == null) {
            return "no throwable available";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("exception-chain:");
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        Throwable cursor = error;
        int depth = 0;
        while (cursor != null && seen.add(cursor)) {
            builder.append('\n')
                    .append("  [")
                    .append(depth)
                    .append("] ")
                    .append(cursor.getClass().getName());
            String message = cursor.getMessage();
            if (!isBlank(message)) {
                builder.append(": ").append(message);
            }
            cursor = cursor.getCause();
            depth++;
        }
        Throwable root = findRootCause(error);
        if (root != null) {
            builder.append('\n').append("root-cause-stacktrace:").append('\n');
            StringWriter stringWriter = new StringWriter();
            PrintWriter printWriter = new PrintWriter(stringWriter);
            root.printStackTrace(printWriter);
            printWriter.flush();
            builder.append(stringWriter.toString());
        }
        return builder.toString();
    }

    private static String buildBeanFailureReason(Throwable error,
                                                 String failedBeanName,
                                                 String expectedTypeName,
                                                 String actualTypeName) {
        StringBuilder reason = new StringBuilder();
        if (!isBlank(failedBeanName)) {
            reason.append("bean=").append(failedBeanName.trim());
        }
        String failedTypeName = extractFailedTypeName(error);
        if (!isBlank(failedTypeName)) {
            if (reason.length() > 0) {
                reason.append(", ");
            }
            reason.append("failedType=").append(failedTypeName.trim());
        }
        if (!isBlank(expectedTypeName)) {
            if (reason.length() > 0) {
                reason.append(", ");
            }
            reason.append("expectedType=").append(expectedTypeName.trim());
        }
        if (!isBlank(actualTypeName)) {
            if (reason.length() > 0) {
                reason.append(", ");
            }
            reason.append("actualType=").append(actualTypeName.trim());
        }
        Throwable root = findRootCause(error);
        if (root != null) {
            if (reason.length() > 0) {
                reason.append(", ");
            }
            reason.append("rootCause=").append(root.getClass().getName());
            String rootMessage = root.getMessage();
            if (!isBlank(rootMessage)) {
                reason.append(": ").append(rootMessage.trim());
            }
        }
        if (reason.length() == 0) {
            return "unknown";
        }
        return reason.toString();
    }

    private static Throwable findRootCause(Throwable error) {
        if (error == null) {
            return null;
        }
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        Throwable cursor = error;
        Throwable root = error;
        while (cursor != null && seen.add(cursor)) {
            root = cursor;
            cursor = cursor.getCause();
        }
        return root;
    }

    private static boolean isMissingServletEnvironmentClass(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (message != null) {
                if (message.contains("ApplicationServletEnvironment")
                        || message.contains("ApplicaitonServletEnvironment")) {
                    return true;
                }
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static void validate(StartRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (request.getStartupClassName() == null || request.getStartupClassName().trim().isEmpty()) {
            throw new IllegalArgumentException("startupClassName must not be empty");
        }
    }

    private static Class<?> loadClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, true, classLoader);
        } catch (ClassNotFoundException notFound) {
            throw new IllegalArgumentException("Cannot load startup class: " + className, notFound);
        }
    }

    private static ClassLoader resolveExecutionClassLoader(StartRequest request, ClassLoader fallbackClassLoader) {
        if (request.getProjectDir() == null || request.getProjectDir().trim().isEmpty()) {
            return fallbackClassLoader;
        }
        Path projectDir = Paths.get(request.getProjectDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(projectDir)) {
            throw new IllegalArgumentException("project-dir does not exist: " + projectDir);
        }
        List<URL> urls = collectProjectClasspathUrls(projectDir);
        if (urls.isEmpty()) {
            throw new IllegalArgumentException(
                    "No classpath entries discovered under project-dir: " + projectDir +
                            ". Expect target/classes or target/dependency/*.jar"
            );
        }
        addToolClassesIfPresent(urls);
        return new ChildFirstUrlClassLoader(
                urls.toArray(new URL[0]),
                minimalParentClassLoader()
        );
    }

    private static List<URL> collectProjectClasspathUrls(Path projectDir) {
        LinkedHashSet<URL> urls = new LinkedHashSet<URL>();
        addDirectoryIfPresent(urls, projectDir.resolve("target/classes"));
        addDirectoryIfPresent(urls, projectDir.resolve("target/test-classes"));
        addJarDirectoryIfPresent(urls, projectDir.resolve("target/dependency"));
        addJarDirectoryIfPresent(urls, projectDir.resolve("target/lib"));
        addJarDirectoryIfPresent(urls, projectDir.resolve("libs"));
        return new ArrayList<URL>(urls);
    }

    private static void addDirectoryIfPresent(Set<URL> urls, Path path) {
        if (!Files.isDirectory(path)) {
            return;
        }
        try {
            urls.add(path.toUri().toURL());
        } catch (MalformedURLException malformed) {
            throw new IllegalStateException("Invalid classpath directory: " + path, malformed);
        }
    }

    private static void addJarDirectoryIfPresent(Set<URL> urls, Path jarDir) {
        if (!Files.isDirectory(jarDir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(jarDir, "*.jar")) {
            for (Path path : stream) {
                try {
                    urls.add(path.toUri().toURL());
                } catch (MalformedURLException malformed) {
                    throw new IllegalStateException("Invalid classpath jar: " + path, malformed);
                }
            }
        } catch (IOException io) {
            throw new IllegalStateException("Failed to scan classpath jars under: " + jarDir, io);
        }
    }

    private static void addToolClassesIfPresent(List<URL> urls) {
        try {
            if (SpringBootNativeLauncher.class.getProtectionDomain() == null
                    || SpringBootNativeLauncher.class.getProtectionDomain().getCodeSource() == null
                    || SpringBootNativeLauncher.class.getProtectionDomain().getCodeSource().getLocation() == null) {
                return;
            }
            URL toolLocation = SpringBootNativeLauncher.class.getProtectionDomain().getCodeSource().getLocation();
            urls.remove(toolLocation);
            // Keep analyzer classes first to avoid being shadowed by same-package classes from target project jars.
            urls.add(0, toolLocation);
        } catch (Exception ignored) {
            // Ignore missing tool classes path and rely on current class loader fallback.
        }
    }

    private static ClassLoader minimalParentClassLoader() {
        try {
            Method method = ClassLoader.class.getMethod("getPlatformClassLoader");
            Object loader = method.invoke(null);
            if (loader instanceof ClassLoader) {
                return (ClassLoader) loader;
            }
        } catch (Exception ignored) {
            // Java 8 has no platform class loader API.
        }
        return null;
    }

    private static void suppressSpringApplicationListeners(Object builder) {
        try {
            Method applicationMethod = builder.getClass().getMethod("application");
            Object springApplication = applicationMethod.invoke(builder);
            if (springApplication == null) {
                return;
            }
            Method setListeners = springApplication.getClass().getMethod("setListeners", Collection.class);
            setListeners.invoke(springApplication, Collections.emptyList());
            LOG.info("Suppressed SpringApplication listeners upfront.");
        } catch (Exception error) {
            LOG.warn("Failed suppressing SpringApplication listeners upfront.", error);
        }
    }

    private static void suppressSpringApplicationCallbacks(Object builder) {
        try {
            Method applicationMethod = builder.getClass().getMethod("application");
            Object springApplication = applicationMethod.invoke(builder);
            if (springApplication == null) {
                return;
            }
            Method getInitializers = springApplication.getClass().getMethod("getInitializers");
            Object initializersObject = getInitializers.invoke(springApplication);
            List<Object> retainedInitializers = new ArrayList<Object>();
            if (initializersObject instanceof Collection) {
                Collection<?> initializers = (Collection<?>) initializersObject;
                for (Object initializer : initializers) {
                    if (initializer == null) {
                        continue;
                    }
                    String marker = String.valueOf(initializer);
                    if (marker.contains("MockingInitializerProxy")) {
                        retainedInitializers.add(initializer);
                    }
                }
            }
            Method setInitializers = springApplication.getClass().getMethod("setInitializers", Collection.class);
            setInitializers.invoke(springApplication, retainedInitializers);
            Method setListeners = springApplication.getClass().getMethod("setListeners", Collection.class);
            setListeners.invoke(springApplication, Collections.emptyList());
            LOG.warn("Suppressed SpringApplication callbacks: retainedInitializers={} listeners=0", retainedInitializers.size());
        } catch (Exception error) {
            LOG.warn("Failed suppressing SpringApplication callbacks.", error);
        }
    }

    private static InitializerHandle createMockingInitializerHandle(final ClassLoader classLoader,
                                                                    final List<String> packagePrefixes,
                                                                    final List<String> forceMockClassPrefixes,
                                                                    final Set<String> forceMockBeanNames,
                                                                    final Map<String, String> forceMockBeanTargetTypes,
                                                                    final Set<String> forceMockMissingTypeNames) {
        try {
            LOG.info(
                    "Create mocking initializer: scanPackages={} forceMockClassPrefixes={} forceMockBeanNames={} forceMockBeanTargetTypes={} forceMockMissingTypeNames={}",
                    packagePrefixes,
                    forceMockClassPrefixes,
                    forceMockBeanNames,
                    forceMockBeanTargetTypes,
                    forceMockMissingTypeNames
            );
            final Class<?> initializerType = Class.forName(
                    "org.springframework.context.ApplicationContextInitializer",
                    true,
                    classLoader
            );
            final Class<?> beanFactoryPostProcessorType = Class.forName(
                    "org.springframework.beans.factory.config.BeanFactoryPostProcessor",
                    true,
                    classLoader
            );
            final Class<?> postProcessorClass = Class.forName(
                    "com.camelot.runtime.bootstrap.DaoMapperMockPostProcessor",
                    true,
                    classLoader
            );
            LOG.info(
                    "Resolved DaoMapperMockPostProcessor classLoader={} codeSource={}",
                    postProcessorClass.getClassLoader(),
                    postProcessorClass.getProtectionDomain() == null
                            || postProcessorClass.getProtectionDomain().getCodeSource() == null
                            || postProcessorClass.getProtectionDomain().getCodeSource().getLocation() == null
                            ? "unknown"
                            : postProcessorClass.getProtectionDomain().getCodeSource().getLocation()
            );
            final Constructor<?> constructor = postProcessorClass.getDeclaredConstructor(
                    List.class,
                    List.class,
                    Set.class,
                    Map.class,
                    List.class,
                    Set.class
            );
            constructor.setAccessible(true);
            final AtomicReference<Object> postProcessorRef = new AtomicReference<Object>();

            InvocationHandler handler = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    String name = method.getName();
                    if ("initialize".equals(name) && args != null && args.length == 1 && args[0] != null) {
                        Object context = args[0];
                        LOG.info(
                                "Initialize mocking post-processor for context={} classLoader={}",
                                context.getClass().getName(),
                                context.getClass().getClassLoader()
                        );
                        List<String> mapperLocations = resolveMapperLocationsFromEnvironment(context);
                        Object postProcessor = constructor.newInstance(
                                new ArrayList<String>(packagePrefixes),
                                new ArrayList<String>(forceMockClassPrefixes),
                                new LinkedHashSet<String>(forceMockBeanNames),
                                new LinkedHashMap<String, String>(forceMockBeanTargetTypes),
                                mapperLocations,
                                new LinkedHashSet<String>(forceMockMissingTypeNames)
                        );
                        postProcessorRef.set(postProcessor);
                        Method addPostProcessor = context.getClass().getMethod(
                                "addBeanFactoryPostProcessor",
                                beanFactoryPostProcessorType
                        );
                        addPostProcessor.invoke(context, postProcessor);
                        LOG.info("Registered DaoMapperMockPostProcessor into Spring context.");
                        return null;
                    }
                    if ("toString".equals(name) && method.getParameterTypes().length == 0) {
                        return "MockingInitializerProxy";
                    }
                    if ("hashCode".equals(name) && method.getParameterTypes().length == 0) {
                        return Integer.valueOf(System.identityHashCode(proxy));
                    }
                    if ("equals".equals(name) && method.getParameterTypes().length == 1) {
                        return Boolean.valueOf(proxy == args[0]);
                    }
                    return null;
                }
            };

            Object initializerProxy = java.lang.reflect.Proxy.newProxyInstance(
                    classLoader,
                    new Class[]{initializerType},
                    handler
            );
            Object initializerArray = Array.newInstance(initializerType, 1);
            Array.set(initializerArray, 0, initializerProxy);
            return new InitializerHandle(
                    initializerArray,
                    initializerArray.getClass(),
                    postProcessorRef
            );
        } catch (Exception error) {
            throw new IllegalStateException("Failed to create cross-classloader mocking initializer", error);
        }
    }

    private static List<String> resolveMapperLocationsFromEnvironment(Object context) {
        if (context == null) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> locations = new LinkedHashSet<String>();
        try {
            Method getEnvironment = context.getClass().getMethod("getEnvironment");
            Object environment = getEnvironment.invoke(context);
            if (environment == null) {
                return Collections.emptyList();
            }
            Method getProperty = environment.getClass().getMethod("getProperty", String.class);
            addLocationValues(locations, getProperty.invoke(environment, "mybatis.mapper-locations"));
            addLocationValues(locations, getProperty.invoke(environment, "mybatis-plus.mapper-locations"));
            addLocationValues(locations, getProperty.invoke(environment, "mybatis.mapperLocations"));
        } catch (Throwable error) {
            LOG.debug("Resolve mapper locations from environment failed.", error);
        }
        if (!locations.isEmpty()) {
            LOG.info("Resolved mapper-locations from environment: {}", locations);
        }
        return locations.isEmpty() ? Collections.<String>emptyList() : new ArrayList<String>(locations);
    }

    private static void addLocationValues(Set<String> target, Object rawValue) {
        if (target == null || rawValue == null) {
            return;
        }
        String text = String.valueOf(rawValue).trim();
        if (text.isEmpty()) {
            return;
        }
        for (String commaSegment : text.split(",")) {
            if (commaSegment == null) {
                continue;
            }
            for (String semicolonSegment : commaSegment.split(";")) {
                if (semicolonSegment == null) {
                    continue;
                }
                String clean = semicolonSegment.trim();
                if (!clean.isEmpty()) {
                    target.add(clean);
                }
            }
        }
    }

    private static List<String> resolveMockScopePackages(StartRequest request, Class<?> startupClass) {
        if (request.getMockPackagePrefixes() != null && !request.getMockPackagePrefixes().isEmpty()) {
            return new ArrayList<String>(new LinkedHashSet<String>(request.getMockPackagePrefixes()));
        }
        LinkedHashSet<String> detected = new LinkedHashSet<String>();
        collectScanPackagesFromAnnotations(startupClass, detected, new HashSet<Class<?>>());
        Package startupPackage = startupClass.getPackage();
        if (startupPackage != null && startupPackage.getName() != null && !startupPackage.getName().trim().isEmpty()) {
            detected.add(startupPackage.getName().trim());
        }
        if (detected.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<String>(detected);
    }

    private static void collectScanPackagesFromAnnotations(Class<?> source,
                                                           Set<String> output,
                                                           Set<Class<?>> visitedAnnotations) {
        Annotation[] annotations;
        try {
            annotations = source.getAnnotations();
        } catch (TypeNotPresentException missingType) {
            return;
        }
        for (Annotation annotation : annotations) {
            Class<? extends Annotation> annotationType = annotation.annotationType();
            collectPackagesFromSingleAnnotation(annotation, output);
            if (!visitedAnnotations.add(annotationType)) {
                continue;
            }
            collectScanPackagesFromAnnotationType(annotationType, output, visitedAnnotations);
        }
    }

    private static void collectScanPackagesFromAnnotationType(Class<? extends Annotation> annotationType,
                                                              Set<String> output,
                                                              Set<Class<?>> visitedAnnotations) {
        Annotation[] annotations;
        try {
            annotations = annotationType.getAnnotations();
        } catch (TypeNotPresentException missingType) {
            return;
        }
        for (Annotation meta : annotations) {
            Class<? extends Annotation> metaType = meta.annotationType();
            collectPackagesFromSingleAnnotation(meta, output);
            if (!visitedAnnotations.add(metaType)) {
                continue;
            }
            collectScanPackagesFromAnnotationType(metaType, output, visitedAnnotations);
        }
    }

    private static void collectPackagesFromSingleAnnotation(Annotation annotation, Set<String> output) {
        Class<? extends Annotation> annotationType = annotation.annotationType();
        String annotationName = annotationType.getName();
        if (!SCAN_ANNOTATION_NAMES.contains(annotationName)) {
            return;
        }
        collectStringArrayAttribute(annotation, "scanBasePackages", output);
        collectStringArrayAttribute(annotation, "basePackages", output);
        if ("org.springframework.context.annotation.ComponentScan".equals(annotationName)
                || "org.mybatis.spring.annotation.MapperScan".equals(annotationName)) {
            collectStringArrayAttribute(annotation, "value", output);
        }
        collectClassArrayAttribute(annotation, "scanBasePackageClasses", output);
        collectClassArrayAttribute(annotation, "basePackageClasses", output);
    }

    private static void collectStringArrayAttribute(Annotation annotation, String attributeName, Set<String> output) {
        try {
            Method method = annotation.annotationType().getMethod(attributeName);
            Object value = method.invoke(annotation);
            if (!(value instanceof String[])) {
                return;
            }
            for (String raw : (String[]) value) {
                if (raw == null) {
                    continue;
                }
                String clean = raw.trim();
                if (!clean.isEmpty()) {
                    output.add(clean);
                }
            }
        } catch (NoSuchMethodException ignored) {
            // annotation has no such attribute
        } catch (Exception ignored) {
            // ignore annotation parse failures, fallback to startup package
        }
    }

    private static void collectClassArrayAttribute(Annotation annotation, String attributeName, Set<String> output) {
        try {
            Method method = annotation.annotationType().getMethod(attributeName);
            Object value = method.invoke(annotation);
            if (!(value instanceof Class[])) {
                return;
            }
            for (Class<?> type : (Class<?>[]) value) {
                if (type == null || type.getPackage() == null) {
                    continue;
                }
                String packageName = type.getPackage().getName();
                if (packageName != null && !packageName.trim().isEmpty()) {
                    output.add(packageName.trim());
                }
            }
        } catch (NoSuchMethodException ignored) {
            // annotation has no such attribute
        } catch (Exception ignored) {
            // ignore annotation parse failures, fallback to startup package
        }
    }

    private static Map<String, String> mergeLaunchProperties(Map<String, String> extra) {
        LinkedHashMap<String, String> properties = new LinkedHashMap<String, String>();
        properties.put("spring.main.allow-bean-definition-overriding", "true");
        properties.put("spring.main.lazy-initialization", "true");
        properties.put("spring.task.scheduling.enabled", "false");
        properties.put("spring.sql.init.mode", "never");
        properties.put("spring.flyway.enabled", "false");
        properties.put("spring.liquibase.enabled", "false");
        properties.put(
                "spring.autoconfigure.exclude",
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration," +
                        "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration"
        );
        if (extra != null) {
            properties.putAll(extra);
        }
        return properties;
    }

    private static List<String> mapToKeyValueArgs(Map<String, String> properties) {
        List<String> args = new ArrayList<String>(properties.size());
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            args.add(entry.getKey() + "=" + entry.getValue());
        }
        return args;
    }

    private static Object createBuilderInstance(Class<?> startupClass, ClassLoader classLoader) {
        try {
            Class<?> builderType = Class.forName(SPRING_BOOT_BUILDER_CLASS, true, classLoader);
            Constructor<?> constructor = builderType.getConstructor(Class[].class);
            return constructor.newInstance((Object) new Class[]{startupClass});
        } catch (ClassNotFoundException missingBoot) {
            throw new IllegalStateException(
                    "Cannot find org.springframework.boot.builder.SpringApplicationBuilder on runtime classpath",
                    missingBoot
            );
        } catch (Exception error) {
            throw new IllegalStateException("Failed to create SpringApplicationBuilder", error);
        }
    }

    private static Object invokeBuilder(Object builder, String methodName, Class<?>[] paramTypes, Object[] args) {
        try {
            Method method = builder.getClass().getMethod(methodName, paramTypes);
            return method.invoke(builder, args);
        } catch (Exception error) {
            throw new IllegalStateException("Failed invoking SpringApplicationBuilder." + methodName + "(...)", error);
        }
    }

    private static final class InitializerHandle {
        private final Object initializerArray;
        private final Class<?> initializerArrayType;
        private final AtomicReference<Object> postProcessorRef;

        private InitializerHandle(Object initializerArray,
                                  Class<?> initializerArrayType,
                                  AtomicReference<Object> postProcessorRef) {
            this.initializerArray = initializerArray;
            this.initializerArrayType = initializerArrayType;
            this.postProcessorRef = postProcessorRef;
        }

        private Map<String, String> snapshotMockedBeanTypes() {
            Object postProcessor = postProcessorRef.get();
            if (postProcessor == null) {
                return Collections.emptyMap();
            }
            try {
                Method snapshotMethod = postProcessor.getClass().getDeclaredMethod("snapshotMockedBeanTypes");
                snapshotMethod.setAccessible(true);
                Object result = snapshotMethod.invoke(postProcessor);
                if (!(result instanceof Map)) {
                    return Collections.emptyMap();
                }
                Map<?, ?> source = (Map<?, ?>) result;
                LinkedHashMap<String, String> converted = new LinkedHashMap<String, String>();
                for (Map.Entry<?, ?> entry : source.entrySet()) {
                    converted.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
                return converted;
            } catch (Exception ignored) {
                return Collections.emptyMap();
            }
        }
    }

    private static final class RunOutcome {
        private final Object contextObject;
        private final Map<String, String> mockedBeanTypes;

        private RunOutcome(Object contextObject, Map<String, String> mockedBeanTypes) {
            this.contextObject = contextObject;
            this.mockedBeanTypes = mockedBeanTypes;
        }
    }

    private static final class ChildFirstUrlClassLoader extends URLClassLoader {

        private ChildFirstUrlClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (isParentFirstClass(name)) {
                    loaded = super.loadClass(name, false);
                } else {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException notInChild) {
                        loaded = super.loadClass(name, false);
                    }
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }

        private boolean isParentFirstClass(String name) {
            return name.startsWith("java.")
                    || name.startsWith("javax.")
                    || name.startsWith("jakarta.")
                    || name.startsWith("org.xml.")
                    || name.startsWith("org.w3c.")
                    || name.startsWith("org.xml.sax.")
                    || name.startsWith("sun.")
                    || name.startsWith("com.sun.")
                    || name.startsWith("jdk.");
        }
    }

    public static final class StartRequest {
        private String startupClassName;
        private String projectDir;
        private List<String> activeProfiles = Collections.singletonList("test");
        private List<String> mockPackagePrefixes = Collections.emptyList();
        private List<String> forceMockClassPrefixes = Collections.emptyList();
        private List<String> applicationArgs = Collections.emptyList();
        private Map<String, String> extraProperties = Collections.emptyMap();

        public String getStartupClassName() {
            return startupClassName;
        }

        public void setStartupClassName(String startupClassName) {
            this.startupClassName = startupClassName;
        }

        public String getProjectDir() {
            return projectDir;
        }

        public void setProjectDir(String projectDir) {
            this.projectDir = projectDir;
        }

        public List<String> getActiveProfiles() {
            return activeProfiles;
        }

        public void setActiveProfiles(List<String> activeProfiles) {
            if (activeProfiles == null || activeProfiles.isEmpty()) {
                this.activeProfiles = Collections.singletonList("test");
                return;
            }
            this.activeProfiles = activeProfiles;
        }

        public List<String> getMockPackagePrefixes() {
            return mockPackagePrefixes;
        }

        public void setMockPackagePrefixes(List<String> mockPackagePrefixes) {
            this.mockPackagePrefixes = mockPackagePrefixes == null ? Collections.<String>emptyList() : mockPackagePrefixes;
        }

        public List<String> getForceMockClassPrefixes() {
            return forceMockClassPrefixes;
        }

        public void setForceMockClassPrefixes(List<String> forceMockClassPrefixes) {
            this.forceMockClassPrefixes = forceMockClassPrefixes == null
                    ? Collections.<String>emptyList()
                    : forceMockClassPrefixes;
        }

        public List<String> getApplicationArgs() {
            return applicationArgs;
        }

        public void setApplicationArgs(List<String> applicationArgs) {
            this.applicationArgs = applicationArgs == null ? Collections.<String>emptyList() : applicationArgs;
        }

        public Map<String, String> getExtraProperties() {
            return extraProperties;
        }

        public void setExtraProperties(Map<String, String> extraProperties) {
            this.extraProperties = extraProperties == null ? Collections.<String, String>emptyMap() : extraProperties;
        }
    }

    public static final class StartResult {
        private final Object context;
        private final String startupClassName;
        private final List<String> activeProfiles;
        private final Map<String, String> mockedBeanTypes;
        private final Map<String, String> launchProperties;

        StartResult(Object context,
                    String startupClassName,
                    List<String> activeProfiles,
                    Map<String, String> mockedBeanTypes,
                    Map<String, String> launchProperties) {
            this.context = context;
            this.startupClassName = startupClassName;
            this.activeProfiles = activeProfiles;
            this.mockedBeanTypes = mockedBeanTypes;
            this.launchProperties = launchProperties;
        }

        public Object getContext() {
            return context;
        }

        public String getStartupClassName() {
            return startupClassName;
        }

        public List<String> getActiveProfiles() {
            return activeProfiles;
        }

        public Map<String, String> getMockedBeanTypes() {
            return mockedBeanTypes;
        }

        public Map<String, String> getLaunchProperties() {
            return launchProperties;
        }
    }
}
