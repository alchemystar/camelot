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
        // Replace in postProcessBeanFactory so component-scan and mapper-scan definitions are already registered.
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        if (!(beanFactory instanceof BeanDefinitionRegistry)) {
            return;
        }
        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
        List<String> beanNames = new ArrayList<String>(Arrays.asList(beanFactory.getBeanDefinitionNames()));
        for (String beanName : beanNames) {
            BeanDefinition definition = beanFactory.getBeanDefinition(beanName);
            boolean forcedByBeanName = beanName != null && forceMockBeanNames.contains(beanName);
            MockTarget target = resolveTarget(beanFactory, beanName, definition);
            if (target == null && forcedByBeanName) {
                target = new MockTarget(Object.class.getName(), false);
            }
            if (target == null) {
                continue;
            }
            if (!forcedByBeanName && !shouldMock(beanName, target)) {
                continue;
            }
            RootBeanDefinition replacement = new RootBeanDefinition(NoOpBeanMockFactoryBean.class);
            replacement.getPropertyValues().add("targetTypeName", target.typeName);
            replacement.setPrimary(definition.isPrimary());
            replacement.setLazyInit(true);
            replacement.setRole(definition.getRole());
            replacement.setScope(definition.getScope() == null ? BeanDefinition.SCOPE_SINGLETON : definition.getScope());
            registry.removeBeanDefinition(beanName);
            registry.registerBeanDefinition(beanName, replacement);
            mockedBeanTypes.put(beanName, target.typeName);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    Map<String, String> snapshotMockedBeanTypes() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, String>(mockedBeanTypes));
    }

    private boolean shouldMock(String beanName, MockTarget target) {
        if (beanName != null && forceMockBeanNames.contains(beanName)) {
            return true;
        }
        if (hasAnyPrefix(target.typeName, forceMockTypePrefixes)) {
            return true;
        }
        if (isForceMockType(beanName, target)) {
            return true;
        }
        if (!isBusinessPackage(target.typeName)) {
            return false;
        }
        if (isFrameworkType(target.typeName)) {
            return false;
        }
        if (target.mybatisMapperFactory) {
            return true;
        }
        if (hasAnySuffix(target.typeName, daoMapperSuffixes)) {
            return true;
        }
        return hasAnySuffix(beanName, daoMapperSuffixes);
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

    private boolean hasAnyPrefix(String value, List<String> prefixes) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || prefixes == null || prefixes.isEmpty()) {
            return false;
        }
        for (String prefix : prefixes) {
            if (prefix == null || prefix.trim().isEmpty()) {
                continue;
            }
            String normalized = prefix.trim();
            if (matchesPrefix(clean, normalized)) {
                return true;
            }
        }
        return false;
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

    private MockTarget resolveTarget(ConfigurableListableBeanFactory beanFactory,
                                     String beanName,
                                     BeanDefinition definition) {
        String beanClassName = definition.getBeanClassName();
        if (MYBATIS_MAPPER_FACTORY.equals(beanClassName)) {
            String mapperType = resolveMapperInterfaceType(definition);
            if (mapperType != null) {
                return new MockTarget(mapperType, true);
            }
        }
        if (beanClassName != null && !beanClassName.trim().isEmpty()) {
            return new MockTarget(beanClassName.trim(), false);
        }
        String mapperType = resolveMapperInterfaceType(definition);
        if (mapperType != null) {
            return new MockTarget(mapperType, true);
        }
        try {
            Class<?> beanType = beanFactory.getType(beanName, false);
            if (beanType != null) {
                return new MockTarget(beanType.getName(), false);
            }
        } catch (Throwable ignored) {
            // Best effort only.
        }
        Object factoryType = definition.getAttribute("factoryBeanObjectType");
        String factoryTypeName = asTypeName(factoryType);
        if (factoryTypeName != null) {
            return new MockTarget(factoryTypeName, false);
        }
        try {
            Class<?> resolved = definition.getResolvableType().resolve();
            if (resolved != null) {
                return new MockTarget(resolved.getName(), false);
            }
        } catch (Throwable ignored) {
            // Best effort only.
        }
        String factoryMethodType = resolveFactoryMethodType(beanFactory, definition);
        if (factoryMethodType != null) {
            return new MockTarget(factoryMethodType, false);
        }
        return null;
    }

    private String resolveFactoryMethodType(ConfigurableListableBeanFactory beanFactory, BeanDefinition definition) {
        String factoryMethodName = clean(definition.getFactoryMethodName());
        if (factoryMethodName == null) {
            return null;
        }
        String factoryClassName = resolveFactoryClassName(beanFactory, definition);
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

    private String resolveFactoryClassName(ConfigurableListableBeanFactory beanFactory, BeanDefinition definition) {
        String direct = clean(definition.getBeanClassName());
        if (direct != null) {
            return direct;
        }
        String factoryBeanName = clean(definition.getFactoryBeanName());
        if (factoryBeanName == null) {
            return null;
        }
        try {
            Class<?> factoryBeanType = beanFactory.getType(factoryBeanName, false);
            if (factoryBeanType != null) {
                return factoryBeanType.getName();
            }
        } catch (Throwable ignored) {
            // Best effort only.
        }
        if (beanFactory.containsBeanDefinition(factoryBeanName)) {
            BeanDefinition factoryBeanDefinition = beanFactory.getBeanDefinition(factoryBeanName);
            String className = clean(factoryBeanDefinition.getBeanClassName());
            if (className != null) {
                return className;
            }
            String attributeType = asTypeName(factoryBeanDefinition.getAttribute("factoryBeanObjectType"));
            return clean(attributeType);
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
        if (source instanceof BeanDefinitionHolder) {
            BeanDefinitionHolder holder = (BeanDefinitionHolder) source;
            return holder.getBeanDefinition().getBeanClassName();
        }
        if (source instanceof String) {
            String text = ((String) source).trim();
            return text.isEmpty() ? null : text;
        }
        return null;
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
}
