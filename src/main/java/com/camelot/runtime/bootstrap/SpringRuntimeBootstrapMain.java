package com.camelot.runtime.bootstrap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SpringRuntimeBootstrapMain {

    private SpringRuntimeBootstrapMain() {
    }

    public static void main(String[] args) {
        CliOptions options = CliOptions.parse(args);
        SpringBootNativeLauncher launcher = new SpringBootNativeLauncher();
        SpringBootNativeLauncher.StartResult result = launcher.start(options.toRequest());

        System.out.println("Spring context started.");
        System.out.println("Startup class: " + result.getStartupClassName());
        System.out.println("Profiles: " + result.getActiveProfiles());
        System.out.println("Mocked bean count: " + result.getMockedBeanTypes().size());
        for (Map.Entry<String, String> entry : result.getMockedBeanTypes().entrySet()) {
            System.out.println("  - " + entry.getKey() + " -> " + entry.getValue());
        }
        if (options.entrySignature != null && !options.entrySignature.trim().isEmpty()) {
            EntryCallChainExecutor.InvokeResult invokeResult = EntryCallChainExecutor.invokeAndWriteDot(
                    result.getContext(),
                    options.entrySignature,
                    options.dotFilePath
            );
            System.out.println("Entry invoked: " + invokeResult.getSignature());
            System.out.println("Entry method: " + invokeResult.getDeclaringClass() + "#" + invokeResult.getMethodName());
            System.out.println("Entry return: " + String.valueOf(invokeResult.getReturnValue()));
            System.out.println("Call chain dot: " + invokeResult.getDotPath());
        }

        if (options.keepRunning) {
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    safeClose(result.getContext());
                }
            }, "spring-runtime-shutdown"));
            waitForever();
            return;
        }

        safeClose(result.getContext());
    }

    private static void waitForever() {
        Object monitor = new Object();
        synchronized (monitor) {
            try {
                monitor.wait();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void safeClose(Object context) {
        if (context == null) {
            return;
        }
        try {
            context.getClass().getMethod("close").invoke(context);
        } catch (Exception ignored) {
            // Ignore close failures for one-shot runtime bootstrap.
        }
    }

    static final class CliOptions {
        private String startupClassName;
        private String projectDir;
        private final Set<String> profiles = new LinkedHashSet<String>();
        private final Set<String> scanPackages = new LinkedHashSet<String>();
        private final List<String> forceMockClassPrefixes = new ArrayList<String>();
        private final List<String> appArgs = new ArrayList<String>();
        private final Map<String, String> extraProperties = new LinkedHashMap<String, String>();
        private boolean keepRunning = true;
        private String entrySignature;
        private String dotFilePath = "call-chain.dot";

        private CliOptions() {
            this.profiles.add("test");
        }

        static CliOptions parse(String[] args) {
            CliOptions options = new CliOptions();
            for (String arg : args) {
                if (arg == null || arg.trim().isEmpty()) {
                    continue;
                }
                if (arg.startsWith("--startup-class=")) {
                    options.startupClassName = arg.substring("--startup-class=".length()).trim();
                    continue;
                }
                if (arg.startsWith("--project-dir=")) {
                    options.projectDir = arg.substring("--project-dir=".length()).trim();
                    continue;
                }
                if (arg.startsWith("--profile=")) {
                    options.profiles.clear();
                    addCommaSeparated(options.profiles, arg.substring("--profile=".length()));
                    continue;
                }
                if (arg.startsWith("--scan-package=")) {
                    addCommaSeparated(options.scanPackages, arg.substring("--scan-package=".length()));
                    continue;
                }
                if (arg.startsWith("--force-mock-class-prefix=")) {
                    addCommaSeparatedToList(options.forceMockClassPrefixes, arg.substring("--force-mock-class-prefix=".length()));
                    continue;
                }
                if (arg.startsWith("--arg=")) {
                    options.appArgs.add(arg.substring("--arg=".length()));
                    continue;
                }
                if (arg.startsWith("--property=")) {
                    String raw = arg.substring("--property=".length());
                    int split = raw.indexOf('=');
                    if (split <= 0 || split == raw.length() - 1) {
                        throw new IllegalArgumentException("Invalid --property format: " + arg);
                    }
                    String key = raw.substring(0, split).trim();
                    String value = raw.substring(split + 1).trim();
                    options.extraProperties.put(key, value);
                    continue;
                }
                if (arg.startsWith("--entry=")) {
                    options.entrySignature = arg.substring("--entry=".length()).trim();
                    continue;
                }
                if (arg.startsWith("--dot-file=")) {
                    options.dotFilePath = arg.substring("--dot-file=".length()).trim();
                    continue;
                }
                if ("--keep-running".equals(arg)) {
                    options.keepRunning = true;
                    continue;
                }
                throw new IllegalArgumentException("Unsupported argument: " + arg);
            }
            if (options.startupClassName == null || options.startupClassName.trim().isEmpty()) {
                throw new IllegalArgumentException("Missing required argument: --startup-class=<fqcn>");
            }
            if (options.profiles.isEmpty()) {
                options.profiles.add("test");
            }
            return options;
        }

        SpringBootNativeLauncher.StartRequest toRequest() {
            SpringBootNativeLauncher.StartRequest request = new SpringBootNativeLauncher.StartRequest();
            request.setStartupClassName(startupClassName);
            request.setProjectDir(projectDir);
            request.setActiveProfiles(new ArrayList<String>(profiles));
            request.setMockPackagePrefixes(new ArrayList<String>(scanPackages));
            request.setForceMockClassPrefixes(new ArrayList<String>(forceMockClassPrefixes));
            request.setApplicationArgs(new ArrayList<String>(appArgs));
            request.setExtraProperties(new LinkedHashMap<String, String>(extraProperties));
            return request;
        }

        private static void addCommaSeparated(Set<String> target, String raw) {
            if (raw == null || raw.trim().isEmpty()) {
                return;
            }
            List<String> values = Arrays.asList(raw.split(","));
            for (String value : values) {
                String trimmed = value == null ? "" : value.trim();
                if (!trimmed.isEmpty()) {
                    target.add(trimmed);
                }
            }
        }

        private static void addCommaSeparatedToList(List<String> target, String raw) {
            if (raw == null || raw.trim().isEmpty()) {
                return;
            }
            List<String> values = Arrays.asList(raw.split(","));
            for (String value : values) {
                String trimmed = value == null ? "" : value.trim();
                if (!trimmed.isEmpty()) {
                    target.add(trimmed);
                }
            }
        }
    }
}
