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
import org.springframework.core.ResolvableType;
import org.springframework.core.type.MethodMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        if (target == null) {
            target = new MockTarget(evaluation.matchedTypeName, false);
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

        addCandidate(candidates, asTypeName(definition.getAttribute("factoryBeanObjectType")));
        addCandidate(candidates, asTypeName(definition.getAttribute("targetType")));
        addCandidate(candidates, resolveResolvableTypeName(definition.getResolvableType()));
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
        candidates.add(clean);
    }

    private String resolveResolvableTypeName(ResolvableType resolvableType) {
        if (resolvableType == null || ResolvableType.NONE.equals(resolvableType)) {
            return null;
        }
        Class<?> resolved = resolvableType.resolve();
        if (resolved != null) {
            return resolved.getName();
        }
        return extractRawTypeName(resolvableType.toString());
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

    private String asTypeName(Object source) {
        if (source == null) {
            return null;
        }
        if (source instanceof Class) {
            return ((Class<?>) source).getName();
        }
        if (source instanceof ResolvableType) {
            return resolveResolvableTypeName((ResolvableType) source);
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
