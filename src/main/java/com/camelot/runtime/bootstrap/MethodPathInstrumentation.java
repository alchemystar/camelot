package com.camelot.runtime.bootstrap;

import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isNative;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;

final class MethodPathInstrumentation {

    private static final Logger LOG = LoggerFactory.getLogger(MethodPathInstrumentation.class);
    private static volatile boolean installed = false;

    private MethodPathInstrumentation() {
    }

    static synchronized void installIfNeeded(final List<String> packagePrefixes) {
        if (installed) {
            return;
        }
        final List<String> prefixes = normalizePackages(packagePrefixes);
        if (prefixes.isEmpty()) {
            installed = true;
            return;
        }
        Instrumentation instrumentation;
        try {
            System.setProperty("jdk.attach.allowAttachSelf", "true");
            instrumentation = ByteBuddyAgent.install();
        } catch (Throwable attachError) {
            LOG.warn("Method-path instrumentation disabled: attach agent failed. fallback=bean-level tracing.");
            LOG.debug("Method-path instrumentation attach failure details", attachError);
            installed = true;
            return;
        }
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .ignore(nameStartsWith("java.")
                        .or(nameStartsWith("javax."))
                        .or(nameStartsWith("jakarta."))
                        .or(nameStartsWith("sun."))
                        .or(nameStartsWith("com.sun."))
                        .or(nameStartsWith("jdk."))
                        .or(nameStartsWith("net.bytebuddy."))
                        .or(nameStartsWith("org.springframework."))
                        .or(nameStartsWith("com.camelot.runtime.")))
                .type(new AgentBuilder.RawMatcher() {
                    @Override
                    public boolean matches(TypeDescription typeDescription,
                                           ClassLoader classLoader,
                                           JavaModule module,
                                           Class<?> classBeingRedefined,
                                           ProtectionDomain protectionDomain) {
                        return matchesAnyPrefix(typeDescription, prefixes);
                    }
                })
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder,
                                                            TypeDescription typeDescription,
                                                            ClassLoader classLoader,
                                                            JavaModule module,
                                                            ProtectionDomain protectionDomain) {
                        return builder.visit(Advice.to(MethodPathAdvice.class)
                                .on(isMethod()
                                        .and(not(isAbstract()))
                                        .and(not(isNative()))
                                        .and(not(isSynthetic()))));
                    }
                })
                .installOn(instrumentation);
        installed = true;
        LOG.info("Method-path instrumentation installed for package prefixes: {}", prefixes);
    }

    private static boolean matchesAnyPrefix(TypeDescription typeDescription, List<String> prefixes) {
        if (typeDescription == null || prefixes == null || prefixes.isEmpty()) {
            return false;
        }
        String name = typeDescription.getName();
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        for (String prefix : prefixes) {
            if (name.equals(prefix) || name.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> normalizePackages(List<String> prefixes) {
        if (prefixes == null || prefixes.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> normalized = new LinkedHashSet<String>();
        for (String prefix : prefixes) {
            if (prefix == null) {
                continue;
            }
            String clean = prefix.trim();
            if (!clean.isEmpty()) {
                normalized.add(clean);
            }
        }
        return normalized.isEmpty() ? Collections.<String>emptyList() : new ArrayList<String>(normalized);
    }
}
