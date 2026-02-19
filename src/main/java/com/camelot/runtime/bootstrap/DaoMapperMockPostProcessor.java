package com.camelot.runtime.bootstrap;

import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.type.MethodMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class DaoMapperMockPostProcessor implements BeanDefinitionRegistryPostProcessor, PriorityOrdered {

    private static final Logger LOG = LoggerFactory.getLogger(DaoMapperMockPostProcessor.class);
    private static final String MYBATIS_MAPPER_FACTORY = "org.mybatis.spring.mapper.MapperFactoryBean";

    private final List<String> packagePrefixes;
    private final Set<String> daoMapperSuffixes;
    private final Set<String> forceMockSuffixes;
    private final Set<String> forceMockTypeNames;
    private final List<String> forceMockTypePrefixes;
    private final Set<String> forceMockBeanNames;
    private final Map<String, String> mockedBeanTypes = new LinkedHashMap<String, String>();

    DaoMapperMockPostProcessor(List<String> packagePrefixes,
                               List<String> forceMockTypePrefixes,
                               Set<String> forceMockBeanNames) {
        this.packagePrefixes = normalizePackages(packagePrefixes);
        this.daoMapperSuffixes = new LinkedHashSet<String>(Arrays.asList("Dao", "Mapper"));
        this.forceMockSuffixes = new LinkedHashSet<String>(Arrays.asList("DataSource"));
        this.forceMockTypeNames = new LinkedHashSet<String>(Arrays.asList(
                "javax.sql.DataSource",
                "jakarta.sql.DataSource"
        ));
        this.forceMockTypePrefixes = normalizePackages(forceMockTypePrefixes);
        this.forceMockBeanNames = forceMockBeanNames == null
                ? Collections.<String>emptySet()
                : new LinkedHashSet<String>(forceMockBeanNames);
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        emitDiagnostic("Enter postProcessBeanDefinitionRegistry: registryType=" + registry.getClass().getName());
        LOG.info("Enter postProcessBeanDefinitionRegistry: registryType={}", registry.getClass().getName());
        // Replace in postProcessBeanFactory so component-scan and mapper-scan definitions are already registered.
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        emitDiagnostic("Enter postProcessBeanFactory: beanFactoryType=" + beanFactory.getClass().getName()
                + " beanDefinitionCount=" + beanFactory.getBeanDefinitionCount());
        LOG.info("Enter postProcessBeanFactory: beanFactoryType={} beanDefinitionCount={}",
                beanFactory.getClass().getName(),
                beanFactory.getBeanDefinitionCount());
        if (!(beanFactory instanceof BeanDefinitionRegistry)) {
            LOG.warn("Skip mocking because beanFactory is not BeanDefinitionRegistry: {}",
                    beanFactory.getClass().getName());
            return;
        }
        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
        List<String> beanNames = new ArrayList<String>(Arrays.asList(beanFactory.getBeanDefinitionNames()));
        Set<String> mockedBeanNames = new LinkedHashSet<String>();

        // Pass 1: explicit whitelist pre-filter. Any hit is mocked immediately.
        for (String beanName : beanNames) {
            BeanDefinition definition = beanFactory.getBeanDefinition(beanName);
            if (tryMockByForcePrefix(registry, beanName, definition)) {
                mockedBeanNames.add(beanName);
            }
        }

        // Pass 2: normal DAO/Mapper/DataSource and failed-bean-name forced rules.
        for (String beanName : beanNames) {
            if (mockedBeanNames.contains(beanName)) {
                continue;
            }
            BeanDefinition definition = beanFactory.getBeanDefinition(beanName);
            boolean forcedByBeanName = beanName != null && forceMockBeanNames.contains(beanName);
            MockTarget target = resolveTarget(beanName, definition);
            if (target == null && forcedByBeanName) {
                target = new MockTarget(Object.class.getName(), false);
            }
            if (target == null) {
                continue;
            }
            String reason = forcedByBeanName ? "forced-by-failed-bean-name" : findMockReason(beanName, target);
            if (reason == null) {
                continue;
            }
            replaceWithNoOpMock(registry, beanName, definition, target, reason);
        }
    }

    private boolean tryMockByForcePrefix(BeanDefinitionRegistry registry,
                                         String beanName,
                                         BeanDefinition definition) {
        if (forceMockTypePrefixes.isEmpty()) {
            return false;
        }
        ForcePrefixEvaluation evaluation = evaluateForcePrefix(beanName, definition);
        if (!evaluation.hasMatch()) {
            return false;
        }
        emitDiagnostic("Force-prefix matched bean '" + beanName + "' prefix='" + evaluation.matchedPrefix
                + "' matchedType='" + evaluation.matchedTypeName + "' candidates=" + evaluation.candidateTypes);
        LOG.info(
                "Force-prefix matched bean '{}' prefix='{}' matchedType='{}' candidates={}",
                beanName,
                evaluation.matchedPrefix,
                evaluation.matchedTypeName,
                evaluation.candidateTypes
        );

        MockTarget target = resolveTarget(beanName, definition);
        String thriftServiceInterface = resolveThriftServiceInterfaceType(definition, evaluation);
        if (thriftServiceInterface != null) {
            target = new MockTarget(thriftServiceInterface, false);
            emitDiagnostic("Force-prefix thrift serviceInterface selected bean '" + beanName
                    + "' -> '" + thriftServiceInterface + "'");
            LOG.info("Force-prefix thrift serviceInterface selected bean '{}' -> '{}'",
                    beanName,
                    thriftServiceInterface);
        } else {
            String expectedType = resolveForceMockExpectedType(definition, evaluation);
            if (expectedType != null) {
                target = new MockTarget(expectedType, false);
                emitDiagnostic("Force-prefix expected-type selected bean '" + beanName + "' -> '" + expectedType + "'");
                LOG.info("Force-prefix expected-type selected bean '{}' -> '{}'", beanName, expectedType);
            } else if (target == null) {
                target = new MockTarget(evaluation.matchedTypeName, false);
            }
        }
        try {
            replaceWithNoOpMock(
                    registry,
                    beanName,
                    definition,
                    target,
                    "force-mock-class-prefix(" + evaluation.matchedPrefix + ")"
            );
        } catch (RuntimeException error) {
            LOG.error(
                    "Force-prefix matched bean '{}' but mock replacement failed. prefix='{}' matchedType='{}'",
                    beanName,
                    evaluation.matchedPrefix,
                    evaluation.matchedTypeName,
                    error
            );
            throw error;
        }
        return true;
    }

    private void replaceWithNoOpMock(BeanDefinitionRegistry registry,
                                     String beanName,
                                     BeanDefinition originalDefinition,
                                     MockTarget target,
                                     String reason) {
        RootBeanDefinition replacement = new RootBeanDefinition(NoOpBeanMockFactoryBean.class);
        replacement.getPropertyValues().add("targetTypeName", target.typeName);
        replacement.setPrimary(originalDefinition.isPrimary());
        replacement.setLazyInit(true);
        replacement.setRole(originalDefinition.getRole());
        replacement.setScope(originalDefinition.getScope() == null
                ? BeanDefinition.SCOPE_SINGLETON
                : originalDefinition.getScope());
        registry.removeBeanDefinition(beanName);
        registry.registerBeanDefinition(beanName, replacement);
        mockedBeanTypes.put(beanName, target.typeName);
        emitDiagnostic("Mock bean '" + beanName + "' as '" + target.typeName + "' reason=" + reason);
        LOG.info("Mock bean '{}' as '{}' reason={}", beanName, target.typeName, reason);
    }

    private void emitDiagnostic(String text) {
        System.err.println("[camelot-mock] " + text);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    Map<String, String> snapshotMockedBeanTypes() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, String>(mockedBeanTypes));
    }

    private String findMockReason(String beanName, MockTarget target) {
        String matchedPrefix = findMatchingPrefix(target.typeName, forceMockTypePrefixes);
        if (matchedPrefix != null) {
            return "force-mock-class-prefix(" + matchedPrefix + ")";
        }
        if (isForceMockType(beanName, target)) {
            return "force-mock-external-type";
        }
        if (!isBusinessPackage(target.typeName)) {
            return null;
        }
        if (isFrameworkType(target.typeName)) {
            return null;
        }
        if (target.mybatisMapperFactory) {
            return "mybatis-mapper-factory";
        }
        if (hasAnySuffix(target.typeName, daoMapperSuffixes)) {
            return "dao-mapper-type-suffix";
        }
        if (hasAnySuffix(beanName, daoMapperSuffixes)) {
            return "dao-mapper-beanname-suffix";
        }
        return null;
    }

    private String resolveForceMockExpectedType(BeanDefinition definition,
                                                ForcePrefixEvaluation evaluation) {
        LinkedHashSet<String> expectedCandidates = new LinkedHashSet<String>();
        for (String explicit : collectExplicitExpectedTypeNames(definition)) {
            addCandidate(expectedCandidates, explicit);
        }
        String preferred = pickForceExpectedType(expectedCandidates, evaluation.matchedPrefix);
        if (preferred != null) {
            return preferred;
        }

        LinkedHashSet<String> expandedCandidates = new LinkedHashSet<String>();
        for (String candidate : evaluation.candidateTypes) {
            addCandidate(expandedCandidates, candidate);
            for (String derived : deriveExpectedTypesFromRuntimeType(candidate)) {
                addCandidate(expandedCandidates, derived);
            }
        }
        return pickForceExpectedType(expandedCandidates, evaluation.matchedPrefix);
    }

    private List<String> collectExplicitExpectedTypeNames(BeanDefinition definition) {
        LinkedHashSet<String> candidates = new LinkedHashSet<String>();
        addCandidate(candidates, resolveMapperInterfaceType(definition));
        addCandidate(candidates, resolveServiceInterfaceType(definition));
        addCandidate(candidates, asTypeName(definition.getAttribute("factoryBeanObjectType")));
        addCandidate(candidates, asTypeName(definition.getAttribute("targetType")));
        addCandidate(candidates, resolveResolvableTypeNameFromDefinition(definition));
        addCandidate(candidates, resolveMethodMetadataReturnType(definition));
        addCandidate(candidates, resolveFactoryMethodType(definition));
        return new ArrayList<String>(candidates);
    }

    private String resolveThriftServiceInterfaceType(BeanDefinition definition,
                                                     ForcePrefixEvaluation evaluation) {
        if (!isThriftClientProxyMatch(evaluation)) {
            return null;
        }
        String serviceInterfaceType = resolveServiceInterfaceType(definition);
        if (serviceInterfaceType == null) {
            return null;
        }
        if (isInterfaceType(serviceInterfaceType)) {
            return serviceInterfaceType;
        }
        LOG.warn("ThriftClientProxy matched but serviceInterface is not an interface: {}", serviceInterfaceType);
        return null;
    }

    private boolean isThriftClientProxyMatch(ForcePrefixEvaluation evaluation) {
        if (evaluation == null) {
            return false;
        }
        if (containsIgnoreCase(evaluation.matchedPrefix, "thriftclientproxy")) {
            return true;
        }
        if (containsIgnoreCase(evaluation.matchedTypeName, "thriftclientproxy")) {
            return true;
        }
        for (String candidate : evaluation.candidateTypes) {
            if (containsIgnoreCase(candidate, "thriftclientproxy")) {
                return true;
            }
        }
        return false;
    }

    private boolean containsIgnoreCase(String value, String fragment) {
        if (value == null || fragment == null) {
            return false;
        }
        return value.toLowerCase().contains(fragment.toLowerCase());
    }

    private boolean isInterfaceType(String typeName) {
        String clean = clean(typeName);
        if (clean == null) {
            return false;
        }
        try {
            Class<?> type = Class.forName(clean, false, Thread.currentThread().getContextClassLoader());
            return type.isInterface();
        } catch (Throwable ignored) {
            // If class cannot be loaded now, keep compatibility and trust definition metadata.
            return true;
        }
    }

    private String pickForceExpectedType(Set<String> candidates, String matchedPrefix) {
        String businessFallback = null;
        String nonFrameworkFallback = null;
        for (String candidate : candidates) {
            String clean = clean(candidate);
            if (clean == null) {
                continue;
            }
            if (looksLikeProxyTypeName(clean)) {
                continue;
            }
            if (matchedPrefix != null && matchesPrefix(clean, matchedPrefix)) {
                continue;
            }
            if (isBusinessPackage(clean)) {
                return clean;
            }
            if (!isFrameworkType(clean) && businessFallback == null) {
                businessFallback = clean;
                continue;
            }
            if (nonFrameworkFallback == null) {
                nonFrameworkFallback = clean;
            }
        }
        return businessFallback != null ? businessFallback : nonFrameworkFallback;
    }

    private List<String> deriveExpectedTypesFromRuntimeType(String typeName) {
        String clean = clean(typeName);
        if (clean == null) {
            return Collections.emptyList();
        }
        Class<?> runtimeType;
        try {
            runtimeType = Class.forName(clean, false, Thread.currentThread().getContextClassLoader());
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> derived = new LinkedHashSet<String>();
        Deque<Class<?>> queue = new ArrayDeque<Class<?>>();
        LinkedHashSet<Class<?>> visited = new LinkedHashSet<Class<?>>();
        queue.add(runtimeType);
        while (!queue.isEmpty()) {
            Class<?> current = queue.removeFirst();
            if (current == null || !visited.add(current)) {
                continue;
            }
            for (Class<?> iface : current.getInterfaces()) {
                if (iface != null) {
                    addCandidate(derived, iface.getName());
                    queue.addLast(iface);
                }
            }
            Class<?> superClass = current.getSuperclass();
            if (superClass != null && superClass != Object.class) {
                addCandidate(derived, superClass.getName());
                queue.addLast(superClass);
            }
        }
        return new ArrayList<String>(derived);
    }

    private boolean looksLikeProxyTypeName(String typeName) {
        String clean = clean(typeName);
        if (clean == null) {
            return false;
        }
        if (normalizeCglibTypeName(clean) != null) {
            return true;
        }
        return clean.contains(".$Proxy")
                || clean.startsWith("com.sun.proxy.$Proxy")
                || clean.startsWith("jdk.proxy")
                || clean.contains("$$Lambda$");
    }

    private boolean isBusinessPackage(String className) {
        if (packagePrefixes.isEmpty()) {
            return true;
        }
        for (String prefix : packagePrefixes) {
            if (className.equals(prefix) || className.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    private boolean isFrameworkType(String className) {
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jakarta.")
                || className.startsWith("org.springframework.")
                || className.startsWith("com.camelot.runtime.");
    }

    private boolean isForceMockType(String beanName, MockTarget target) {
        if (beanName != null && "datasource".equalsIgnoreCase(beanName.trim())) {
            return true;
        }
        if (forceMockTypeNames.contains(target.typeName)) {
            return true;
        }
        if (hasAnySuffix(target.typeName, forceMockSuffixes)) {
            return true;
        }
        return hasAnySuffix(beanName, forceMockSuffixes);
    }

    private boolean hasAnySuffix(String value, Set<String> suffixes) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) {
            return false;
        }
        for (String suffix : suffixes) {
            if (clean.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private ForcePrefixEvaluation evaluateForcePrefix(String beanName,
                                                      BeanDefinition definition) {
        List<String> candidates = collectCandidateTypeNames(beanName, definition);
        for (String candidate : candidates) {
            String matchedPrefix = findMatchingPrefix(candidate, forceMockTypePrefixes);
            if (matchedPrefix != null) {
                return ForcePrefixEvaluation.matched(candidate, matchedPrefix, candidates);
            }
        }
        return ForcePrefixEvaluation.unmatched(candidates);
    }

    private String findMatchingPrefix(String value, List<String> prefixes) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || prefixes == null || prefixes.isEmpty()) {
            return null;
        }
        for (String prefix : prefixes) {
            if (prefix == null || prefix.trim().isEmpty()) {
                continue;
            }
            String normalized = prefix.trim();
            if (matchesPrefix(clean, normalized)) {
                return normalized;
            }
        }
        return null;
    }

    private boolean matchesPrefix(String clean, String normalizedPrefix) {
        if (normalizedPrefix.isEmpty()) {
            return false;
        }
        if (looksLikeRegexRule(normalizedPrefix)) {
            try {
                return Pattern.compile(normalizedPrefix).matcher(clean).matches();
            } catch (Exception ignored) {
                // fallback to prefix matching below
            }
        }
        if (clean.equals(normalizedPrefix)) {
            return true;
        }
        if (normalizedPrefix.endsWith(".")) {
            return clean.startsWith(normalizedPrefix);
        }
        if (!clean.startsWith(normalizedPrefix)) {
            return false;
        }
        if (clean.length() == normalizedPrefix.length()) {
            return true;
        }
        char boundary = clean.charAt(normalizedPrefix.length());
        return boundary == '.' || boundary == '$';
    }

    private boolean looksLikeRegexRule(String rule) {
        return rule.indexOf('*') >= 0
                || rule.indexOf('[') >= 0
                || rule.indexOf('(') >= 0
                || rule.indexOf('|') >= 0;
    }

    private MockTarget resolveTarget(String beanName,
                                     BeanDefinition definition) {
        String beanClassName = definition.getBeanClassName();
        if (MYBATIS_MAPPER_FACTORY.equals(beanClassName)) {
            String mapperType = resolveMapperInterfaceType(definition);
            if (mapperType != null) {
                return new MockTarget(mapperType, true);
            }
        }
        for (String candidate : collectCandidateTypeNames(beanName, definition)) {
            return new MockTarget(candidate, false);
        }
        return null;
    }

    private List<String> collectCandidateTypeNames(String beanName,
                                                   BeanDefinition definition) {
        LinkedHashSet<String> candidates = new LinkedHashSet<String>();

        String mapperType = resolveMapperInterfaceType(definition);
        addCandidate(candidates, mapperType);
        addCandidate(candidates, resolveServiceInterfaceType(definition));

        addCandidate(candidates, asTypeName(definition.getAttribute("factoryBeanObjectType")));
        addCandidate(candidates, asTypeName(definition.getAttribute("serviceInterface")));
        addCandidate(candidates, asTypeName(definition.getAttribute("targetType")));
        addCandidate(candidates, resolveResolvableTypeNameFromDefinition(definition));
        addCandidate(candidates, resolveMethodMetadataReturnType(definition));
        addCandidate(candidates, resolveFactoryMethodType(definition));
        addCandidate(candidates, clean(definition.getBeanClassName()));

        return new ArrayList<String>(candidates);
    }

    private void addCandidate(Set<String> candidates, String value) {
        String clean = clean(value);
        if (clean == null) {
            return;
        }
        String normalized = normalizeCglibTypeName(clean);
        if (normalized != null) {
            candidates.add(normalized);
        }
        candidates.add(clean);
    }

    private String normalizeCglibTypeName(String typeName) {
        String clean = clean(typeName);
        if (clean == null) {
            return null;
        }
        int marker = clean.indexOf("$$");
        if (marker <= 0) {
            return null;
        }
        String suffix = clean.substring(marker);
        if (!suffix.startsWith("$$EnhancerBySpringCGLIB$$")
                && !suffix.startsWith("$$EnhancerByCGLIB$$")
                && !suffix.startsWith("$$SpringCGLIB$$")) {
            return null;
        }
        return clean.substring(0, marker);
    }

    private String resolveResolvableTypeNameFromDefinition(BeanDefinition definition) {
        if (definition == null) {
            return null;
        }
        try {
            Method method = definition.getClass().getMethod("getResolvableType");
            Object resolvableType = method.invoke(definition);
            return resolveResolvableTypeName(resolvableType);
        } catch (Throwable ignored) {
            // Compatible with older Spring BeanDefinition APIs.
            return null;
        }
    }

    private String resolveResolvableTypeName(Object resolvableType) {
        if (!isResolvableTypeInstance(resolvableType)) {
            return null;
        }
        Class<?> resolvableTypeClass = resolvableType.getClass();
        try {
            Object noneValue = resolvableTypeClass.getField("NONE").get(null);
            if (resolvableType == noneValue) {
                return null;
            }
        } catch (Throwable ignored) {
            // continue
        }
        Class<?> resolved = null;
        try {
            Method resolveMethod = resolvableTypeClass.getMethod("resolve");
            Object resolvedType = resolveMethod.invoke(resolvableType);
            if (resolvedType instanceof Class) {
                resolved = (Class<?>) resolvedType;
            }
        } catch (Throwable ignored) {
            // continue
        }
        if (resolved != null) {
            return resolved.getName();
        }
        return extractRawTypeName(String.valueOf(resolvableType));
    }

    private boolean isResolvableTypeInstance(Object source) {
        return source != null
                && "org.springframework.core.ResolvableType".equals(source.getClass().getName());
    }

    private String resolveMethodMetadataReturnType(BeanDefinition definition) {
        Object source = definition.getSource();
        if (source instanceof MethodMetadata) {
            return clean(((MethodMetadata) source).getReturnTypeName());
        }
        Object metadata = definition.getAttribute("factoryMethodMetadata");
        if (metadata instanceof MethodMetadata) {
            return clean(((MethodMetadata) metadata).getReturnTypeName());
        }
        return null;
    }

    private String resolveFactoryMethodType(BeanDefinition definition) {
        String factoryMethodName = clean(definition.getFactoryMethodName());
        if (factoryMethodName == null) {
            return null;
        }
        String factoryClassName = resolveFactoryClassName(definition);
        if (factoryClassName == null) {
            return null;
        }
        Class<?> factoryClass;
        try {
            factoryClass = Class.forName(factoryClassName, false, Thread.currentThread().getContextClassLoader());
        } catch (Throwable ignored) {
            return null;
        }
        Method[] methods = factoryClass.getDeclaredMethods();
        String candidate = null;
        for (Method method : methods) {
            if (!factoryMethodName.equals(method.getName())) {
                continue;
            }
            if (Void.TYPE.equals(method.getReturnType())) {
                continue;
            }
            String returnTypeName = method.getReturnType().getName();
            if (candidate == null) {
                candidate = returnTypeName;
            } else if (!candidate.equals(returnTypeName)) {
                return candidate;
            }
        }
        if (candidate != null) {
            return candidate;
        }
        for (Method method : factoryClass.getMethods()) {
            if (factoryMethodName.equals(method.getName()) && !Void.TYPE.equals(method.getReturnType())) {
                return method.getReturnType().getName();
            }
        }
        return null;
    }

    private String resolveFactoryClassName(BeanDefinition definition) {
        String direct = clean(definition.getBeanClassName());
        if (direct != null) {
            return direct;
        }
        Object source = definition.getSource();
        if (source instanceof MethodMetadata) {
            return clean(((MethodMetadata) source).getDeclaringClassName());
        }
        Object metadata = definition.getAttribute("factoryMethodMetadata");
        if (metadata instanceof MethodMetadata) {
            return clean(((MethodMetadata) metadata).getDeclaringClassName());
        }
        return null;
    }

    private String resolveMapperInterfaceType(BeanDefinition definition) {
        PropertyValue mapperInterface = definition.getPropertyValues().getPropertyValue("mapperInterface");
        if (mapperInterface != null) {
            String typeName = asTypeName(mapperInterface.getValue());
            if (typeName != null) {
                return typeName;
            }
        }

        ConstructorArgumentValues values = definition.getConstructorArgumentValues();
        for (ConstructorArgumentValues.ValueHolder valueHolder : values.getIndexedArgumentValues().values()) {
            String typeName = asTypeName(valueHolder.getValue());
            if (typeName != null) {
                return typeName;
            }
        }
        for (ConstructorArgumentValues.ValueHolder valueHolder : values.getGenericArgumentValues()) {
            String typeName = asTypeName(valueHolder.getValue());
            if (typeName != null) {
                return typeName;
            }
        }
        return null;
    }

    private String resolveServiceInterfaceType(BeanDefinition definition) {
        PropertyValue serviceInterface = definition.getPropertyValues().getPropertyValue("serviceInterface");
        if (serviceInterface != null) {
            String typeName = toLooseTypeName(serviceInterface.getValue());
            if (typeName != null) {
                return typeName;
            }
        }
        return toLooseTypeName(definition.getAttribute("serviceInterface"));
    }

    private String toLooseTypeName(Object source) {
        if (source == null) {
            return null;
        }
        if (source instanceof Class) {
            return ((Class<?>) source).getName();
        }
        Object value = source;
        if ("org.springframework.beans.factory.config.TypedStringValue".equals(source.getClass().getName())) {
            try {
                Method getValue = source.getClass().getMethod("getValue");
                Object typedValue = getValue.invoke(source);
                if (typedValue != null) {
                    value = typedValue;
                }
            } catch (Throwable ignored) {
                // keep source.toString fallback
            }
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.startsWith("class ")) {
            text = text.substring("class ".length()).trim();
        } else if (text.startsWith("interface ")) {
            text = text.substring("interface ".length()).trim();
        }
        return clean(text);
    }

    private String asTypeName(Object source) {
        if (source == null) {
            return null;
        }
        if (source instanceof Class) {
            return ((Class<?>) source).getName();
        }
        if (isResolvableTypeInstance(source)) {
            return resolveResolvableTypeName(source);
        }
        if (source instanceof MethodMetadata) {
            return clean(((MethodMetadata) source).getReturnTypeName());
        }
        if (source instanceof BeanDefinitionHolder) {
            BeanDefinitionHolder holder = (BeanDefinitionHolder) source;
            return holder.getBeanDefinition().getBeanClassName();
        }
        if (source instanceof String) {
            String text = ((String) source).trim();
            if (text.isEmpty()) {
                return null;
            }
            if (text.startsWith("class ")) {
                text = text.substring("class ".length()).trim();
            }
            return extractRawTypeName(text);
        }
        return null;
    }

    private String extractRawTypeName(String text) {
        String clean = clean(text);
        if (clean == null) {
            return null;
        }
        int genericStart = clean.indexOf('<');
        if (genericStart > 0) {
            clean = clean.substring(0, genericStart).trim();
        }
        if (clean.endsWith("[]")) {
            return clean;
        }
        if (clean.indexOf(' ') >= 0) {
            return null;
        }
        return clean;
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static List<String> normalizePackages(List<String> prefixes) {
        if (prefixes == null || prefixes.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<String>();
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

    private static final class MockTarget {
        private final String typeName;
        private final boolean mybatisMapperFactory;

        private MockTarget(String typeName, boolean mybatisMapperFactory) {
            this.typeName = typeName;
            this.mybatisMapperFactory = mybatisMapperFactory;
        }
    }

    private static final class ForcePrefixEvaluation {
        private final String matchedTypeName;
        private final String matchedPrefix;
        private final List<String> candidateTypes;

        private ForcePrefixEvaluation(String matchedTypeName,
                                      String matchedPrefix,
                                      List<String> candidateTypes) {
            this.matchedTypeName = matchedTypeName;
            this.matchedPrefix = matchedPrefix;
            this.candidateTypes = candidateTypes == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(candidateTypes));
        }

        private boolean hasMatch() {
            return matchedTypeName != null && matchedPrefix != null;
        }

        private static ForcePrefixEvaluation matched(String matchedTypeName,
                                                     String matchedPrefix,
                                                     List<String> candidateTypes) {
            return new ForcePrefixEvaluation(matchedTypeName, matchedPrefix, candidateTypes);
        }

        private static ForcePrefixEvaluation unmatched(List<String> candidateTypes) {
            return new ForcePrefixEvaluation(null, null, candidateTypes);
        }
    }
}
