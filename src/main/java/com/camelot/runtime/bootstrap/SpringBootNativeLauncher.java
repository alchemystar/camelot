package com.camelot.runtime.bootstrap;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SpringBootNativeLauncher {

    private static final String SPRING_BOOT_BUILDER_CLASS = "org.springframework.boot.builder.SpringApplicationBuilder";
    private static final Set<String> SCAN_ANNOTATION_NAMES = new HashSet<String>(Arrays.asList(
            "org.springframework.context.annotation.ComponentScan",
            "org.springframework.boot.autoconfigure.SpringBootApplication",
            "org.mybatis.spring.annotation.MapperScan"
    ));

    public StartResult start(StartRequest request) {
        validate(request);

        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Class<?> startupClass = loadClass(request.getStartupClassName(), classLoader);
        List<String> packagePrefixes = resolveMockScopePackages(request, startupClass);
        DaoMapperMockPostProcessor mockPostProcessor = new DaoMapperMockPostProcessor(packagePrefixes);

        List<String> activeProfiles = request.getActiveProfiles().isEmpty()
                ? Collections.singletonList("test")
                : request.getActiveProfiles();
        Map<String, String> launchProperties = mergeLaunchProperties(request.getExtraProperties());
        List<String> propertyArgs = mapToKeyValueArgs(launchProperties);

        Object builder = createBuilderInstance(startupClass, classLoader);
        builder = invokeBuilder(builder, "profiles", new Class[]{String[].class}, new Object[]{activeProfiles.toArray(new String[0])});
        builder = invokeBuilder(
                builder,
                "initializers",
                new Class[]{ApplicationContextInitializer[].class},
                new Object[]{new ApplicationContextInitializer[]{new MockingInitializer(mockPostProcessor)}}
        );
        builder = invokeBuilder(builder, "properties", new Class[]{String[].class}, new Object[]{propertyArgs.toArray(new String[0])});

        Object contextObject = invokeBuilder(
                builder,
                "run",
                new Class[]{String[].class},
                new Object[]{request.getApplicationArgs().toArray(new String[0])}
        );
        if (!(contextObject instanceof ConfigurableApplicationContext)) {
            throw new IllegalStateException("Spring context start failed: run(...) did not return ConfigurableApplicationContext");
        }

        ConfigurableApplicationContext context = (ConfigurableApplicationContext) contextObject;
        return new StartResult(
                context,
                startupClass.getName(),
                new ArrayList<String>(activeProfiles),
                mockPostProcessor.snapshotMockedBeanTypes(),
                launchProperties
        );
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

    private static final class MockingInitializer implements ApplicationContextInitializer<GenericApplicationContext> {
        private final DaoMapperMockPostProcessor postProcessor;

        private MockingInitializer(DaoMapperMockPostProcessor postProcessor) {
            this.postProcessor = postProcessor;
        }

        @Override
        public void initialize(GenericApplicationContext context) {
            context.addBeanFactoryPostProcessor(postProcessor);
        }
    }

    public static final class StartRequest {
        private String startupClassName;
        private List<String> activeProfiles = Collections.singletonList("test");
        private List<String> mockPackagePrefixes = Collections.emptyList();
        private List<String> applicationArgs = Collections.emptyList();
        private Map<String, String> extraProperties = Collections.emptyMap();

        public String getStartupClassName() {
            return startupClassName;
        }

        public void setStartupClassName(String startupClassName) {
            this.startupClassName = startupClassName;
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
        private final ConfigurableApplicationContext context;
        private final String startupClassName;
        private final List<String> activeProfiles;
        private final Map<String, String> mockedBeanTypes;
        private final Map<String, String> launchProperties;

        StartResult(ConfigurableApplicationContext context,
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

        public ConfigurableApplicationContext getContext() {
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
