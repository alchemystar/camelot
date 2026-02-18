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
    private final Set<String> targetSuffixes;
    private final Map<String, String> mockedBeanTypes = new LinkedHashMap<String, String>();

    DaoMapperMockPostProcessor(List<String> packagePrefixes) {
        this.packagePrefixes = normalizePackages(packagePrefixes);
        this.targetSuffixes = new LinkedHashSet<String>(Arrays.asList("Dao", "Mapper"));
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
            MockTarget target = resolveTarget(definition);
            if (target == null) {
                continue;
            }
            if (!shouldMock(beanName, target)) {
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
        if (!isBusinessPackage(target.typeName)) {
            return false;
        }
        if (isFrameworkType(target.typeName)) {
            return false;
        }
        if (target.mybatisMapperFactory) {
            return true;
        }
        if (hasTargetSuffix(target.typeName)) {
            return true;
        }
        return hasTargetSuffix(beanName);
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

    private boolean hasTargetSuffix(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) {
            return false;
        }
        for (String suffix : targetSuffixes) {
            if (clean.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private MockTarget resolveTarget(BeanDefinition definition) {
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
        Object factoryType = definition.getAttribute("factoryBeanObjectType");
        String factoryTypeName = asTypeName(factoryType);
        if (factoryTypeName != null) {
            return new MockTarget(factoryTypeName, false);
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
