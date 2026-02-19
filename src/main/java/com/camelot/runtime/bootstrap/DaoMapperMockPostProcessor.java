package com.camelot.runtime.bootstrap;

import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.type.MethodMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.beans.Introspector;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

final class DaoMapperMockPostProcessor implements BeanDefinitionRegistryPostProcessor, PriorityOrdered {

    private static final Logger LOG = LoggerFactory.getLogger(DaoMapperMockPostProcessor.class);
    private static final String MYBATIS_MAPPER_FACTORY = "org.mybatis.spring.mapper.MapperFactoryBean";
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern MAPPER_NAMESPACE_PATTERN =
            Pattern.compile("<mapper\\b[^>]*\\bnamespace\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern YAML_MAPPER_LOCATION_PATTERN =
            Pattern.compile("^\\s*mapper-locations\\s*:\\s*(.+)$");
    private static final Pattern FLAT_MAPPER_LOCATION_PATTERN =
            Pattern.compile("^\\s*(mybatis|mybatis-plus)\\.mapper-locations\\s*[:=]\\s*(.+)$");

    private final List<String> packagePrefixes;
    private final Set<String> daoMapperSuffixes;
    private final Set<String> forceMockSuffixes;
    private final Set<String> forceMockTypeNames;
    private final List<String> forceMockTypePrefixes;
    private final Set<String> forceMockBeanNames;
    private final Map<String, String> forceMockBeanTargetTypes;
    private final List<String> explicitMapperLocations;
    private Set<String> mapperLocationTypeNames = Collections.emptySet();
    private List<String> mapperLocationPackagePrefixes = Collections.emptyList();
    private List<String> mybatisScanPackagePrefixes = Collections.emptyList();
    private final Map<String, String> mockedBeanTypes = new LinkedHashMap<String, String>();

    DaoMapperMockPostProcessor(List<String> packagePrefixes,
                               List<String> forceMockTypePrefixes,
                               Set<String> forceMockBeanNames,
                               Map<String, String> forceMockBeanTargetTypes,
                               List<String> mapperLocations) {
        this.packagePrefixes = normalizePackages(packagePrefixes);
        this.daoMapperSuffixes = new LinkedHashSet<String>(Arrays.asList("DAO", "Mapper"));
        this.forceMockSuffixes = new LinkedHashSet<String>(Arrays.asList("DataSource"));
        this.forceMockTypeNames = new LinkedHashSet<String>(Arrays.asList(
                "javax.sql.DataSource",
                "jakarta.sql.DataSource"
        ));
        this.forceMockTypePrefixes = normalizePackages(forceMockTypePrefixes);
        this.forceMockBeanNames = forceMockBeanNames == null
                ? Collections.<String>emptySet()
                : new LinkedHashSet<String>(forceMockBeanNames);
        this.forceMockBeanTargetTypes = normalizeForceMockBeanTargetTypes(forceMockBeanTargetTypes);
        this.explicitMapperLocations = normalizeMapperLocations(mapperLocations);
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
        LinkedHashSet<String> mapperLocations = new LinkedHashSet<String>(explicitMapperLocations);
        mapperLocations.addAll(discoverMapperLocationsFromEnvironment(beanFactory));
        MapperLocationDiscovery mapperLocationDiscovery = discoverMapperLocationTypes(beanFactory, beanNames, mapperLocations);
        this.mapperLocationTypeNames = mapperLocationDiscovery.mapperTypeNames;
        this.mapperLocationPackagePrefixes = mapperLocationDiscovery.packagePrefixes;
        this.mybatisScanPackagePrefixes = discoverMybatisScanPackages(beanFactory, beanNames);
        applyDefaultPlaceholderValues(beanFactory, beanNames);
        Map<String, String> inferredExpectedTypeByBeanName =
                inferExpectedTypesFromBeanDefinitions(beanFactory, beanNames);
        Set<String> inferredRequiredDaoMapperTypes =
                inferRequiredDaoMapperTypesFromBeanDefinitions(beanFactory, beanNames);
        Set<String> mockedBeanNames = preRegisterRequiredDaoMapperMocks(
                registry,
                beanFactory,
                inferredExpectedTypeByBeanName,
                inferredRequiredDaoMapperTypes
        );
        beanNames = new ArrayList<String>(Arrays.asList(beanFactory.getBeanDefinitionNames()));

        // Pass 1: explicit whitelist pre-filter. Any hit is mocked immediately.
        for (String beanName : beanNames) {
            if (mockedBeanNames.contains(beanName)) {
                continue;
            }
            BeanDefinition definition = beanFactory.getBeanDefinition(beanName);
            String forcedTargetType = findValueByBeanName(forceMockBeanTargetTypes, beanName);
            if (forcedTargetType != null) {
                replaceWithNoOpMock(
                        registry,
                        beanName,
                        definition,
                        new MockTarget(forcedTargetType, false),
                        "forced-by-expected-type"
                );
                mockedBeanNames.add(beanName);
                continue;
            }
            if (tryMockByForcePrefix(registry, beanName, definition, inferredExpectedTypeByBeanName)) {
                mockedBeanNames.add(beanName);
            }
        }

        // Pass 2: normal DAO/Mapper/DataSource and failed-bean-name forced rules.
        for (String beanName : beanNames) {
            if (mockedBeanNames.contains(beanName)) {
                continue;
            }
            BeanDefinition definition = beanFactory.getBeanDefinition(beanName);
            boolean forcedByBeanName = containsBeanName(forceMockBeanNames, beanName);
            String forcedTargetType = findValueByBeanName(forceMockBeanTargetTypes, beanName);
            if (forcedTargetType == null && forcedByBeanName) {
                forcedTargetType = findValueByBeanName(inferredExpectedTypeByBeanName, beanName);
            }
            MockTarget target = resolveTarget(beanName, definition);
            if (forcedTargetType != null) {
                target = new MockTarget(forcedTargetType, false);
            }
            if (target == null && forcedByBeanName) {
                target = forcedTargetType != null
                        ? new MockTarget(forcedTargetType, false)
                        : new MockTarget(Object.class.getName(), false);
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

    private Set<String> preRegisterRequiredDaoMapperMocks(BeanDefinitionRegistry registry,
                                                          ConfigurableListableBeanFactory beanFactory,
                                                          Map<String, String> inferredExpectedTypeByBeanName,
                                                          Set<String> inferredRequiredDaoMapperTypes) {
        LinkedHashSet<String> mockedBeanNames = new LinkedHashSet<String>();
        if (registry == null || beanFactory == null) {
            return mockedBeanNames;
        }

        if (inferredExpectedTypeByBeanName != null && !inferredExpectedTypeByBeanName.isEmpty()) {
            for (Map.Entry<String, String> entry : inferredExpectedTypeByBeanName.entrySet()) {
                if (entry == null) {
                    continue;
                }
                String beanName = clean(entry.getKey());
                String expectedType = clean(entry.getValue());
                if (beanName == null || expectedType == null) {
                    continue;
                }
                if (!isDaoOrMapperType(expectedType)) {
                    continue;
                }
                if (beanFactory.containsBean(beanName) || beanFactory.containsBeanDefinition(beanName)) {
                    continue;
                }
                RootBeanDefinition replacement = buildNoOpMockBeanDefinition(expectedType);
                replacement.setPrimary(true);
                registry.registerBeanDefinition(beanName, replacement);
                mockedBeanTypes.put(beanName, expectedType);
                mockedBeanNames.add(beanName);
                emitDiagnostic("Pre-register mock bean '" + beanName + "' as '" + expectedType
                        + "' reason=required-dao-mapper-by-name");
                LOG.info("Pre-register mock bean '{}' as '{}' reason=required-dao-mapper-by-name",
                        beanName,
                        expectedType);
            }
        }

        if (inferredRequiredDaoMapperTypes != null && !inferredRequiredDaoMapperTypes.isEmpty()) {
            for (String requiredType : inferredRequiredDaoMapperTypes) {
                String typeName = clean(requiredType);
                if (typeName == null || !isDaoOrMapperType(typeName)) {
                    continue;
                }
                if (hasBeanCandidateOfType(beanFactory, typeName)) {
                    continue;
                }
                String generatedBeanName = generateBeanNameForType(registry, typeName);
                RootBeanDefinition replacement = buildNoOpMockBeanDefinition(typeName);
                replacement.setPrimary(true);
                registry.registerBeanDefinition(generatedBeanName, replacement);
                mockedBeanTypes.put(generatedBeanName, typeName);
                mockedBeanNames.add(generatedBeanName);
                emitDiagnostic("Pre-register mock bean '" + generatedBeanName + "' as '" + typeName
                        + "' reason=required-dao-mapper-by-type");
                LOG.info("Pre-register mock bean '{}' as '{}' reason=required-dao-mapper-by-type",
                        generatedBeanName,
                        typeName);
            }
        }
        return mockedBeanNames;
    }

    private RootBeanDefinition buildNoOpMockBeanDefinition(String targetTypeName) {
        RootBeanDefinition replacement = new RootBeanDefinition(NoOpBeanMockFactoryBean.class);
        replacement.getPropertyValues().add("targetTypeName", targetTypeName);
        replacement.setLazyInit(true);
        replacement.setRole(BeanDefinition.ROLE_APPLICATION);
        replacement.setScope(BeanDefinition.SCOPE_SINGLETON);
        return replacement;
    }

    private String generateBeanNameForType(BeanDefinitionRegistry registry, String typeName) {
        String cleanType = clean(typeName);
        String simpleName = cleanType;
        int split = cleanType == null ? -1 : cleanType.lastIndexOf('.');
        if (split >= 0 && split < cleanType.length() - 1) {
            simpleName = cleanType.substring(split + 1);
        }
        if (simpleName == null || simpleName.trim().isEmpty()) {
            simpleName = "daoMapperMock";
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<String>();
        candidates.add(simpleName);
        candidates.add(Introspector.decapitalize(simpleName));
        candidates.add(Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1));
        for (String candidate : candidates) {
            if (candidate == null || candidate.trim().isEmpty()) {
                continue;
            }
            if (!registry.containsBeanDefinition(candidate)) {
                return candidate;
            }
        }
        String baseName = simpleName;
        int index = 1;
        while (true) {
            String candidate = baseName + "#mock" + index;
            if (!registry.containsBeanDefinition(candidate)) {
                return candidate;
            }
            index++;
        }
    }

    private boolean hasBeanCandidateOfType(ConfigurableListableBeanFactory beanFactory, String expectedTypeName) {
        if (beanFactory == null) {
            return false;
        }
        String expected = clean(expectedTypeName);
        if (expected == null) {
            return false;
        }
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition definition = beanFactory.getBeanDefinition(beanName);
            for (String candidate : collectCandidateTypeNames(beanName, definition)) {
                String cleanCandidate = clean(candidate);
                if (cleanCandidate == null) {
                    continue;
                }
                if (expected.equals(cleanCandidate)) {
                    return true;
                }
                String normalizedCandidate = normalizeCglibTypeName(cleanCandidate);
                if (expected.equals(normalizedCandidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean tryMockByForcePrefix(BeanDefinitionRegistry registry,
                                         String beanName,
                                         BeanDefinition definition,
                                         Map<String, String> inferredExpectedTypeByBeanName) {
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
        String inferredExpectedType = inferredExpectedTypeByBeanName == null
                ? null
                : inferredExpectedTypeByBeanName.get(beanName);
        String thriftServiceInterface = resolveThriftServiceInterfaceType(definition, evaluation);
        if (inferredExpectedType != null) {
            target = new MockTarget(inferredExpectedType, false);
            emitDiagnostic("Force-prefix inferred expected-type selected bean '" + beanName
                    + "' -> '" + inferredExpectedType + "'");
            LOG.info("Force-prefix inferred expected-type selected bean '{}' -> '{}'",
                    beanName,
                    inferredExpectedType);
        } else if (thriftServiceInterface != null) {
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

    private MapperLocationDiscovery discoverMapperLocationTypes(ConfigurableListableBeanFactory beanFactory,
                                                                List<String> beanNames,
                                                                Set<String> baseMapperLocations) {
        LinkedHashSet<String> locationPatterns = new LinkedHashSet<String>();
        if (baseMapperLocations != null) {
            locationPatterns.addAll(baseMapperLocations);
        }
        collectMapperLocationsFromBeanDefinitions(beanFactory, beanNames, locationPatterns);
        if (locationPatterns.isEmpty()) {
            return MapperLocationDiscovery.empty();
        }

        LinkedHashSet<String> mapperTypes = new LinkedHashSet<String>();
        LinkedHashSet<String> packagePrefixes = new LinkedHashSet<String>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(
                Thread.currentThread().getContextClassLoader()
        );
        for (String locationPattern : locationPatterns) {
            Resource[] resources;
            try {
                resources = resolver.getResources(locationPattern);
            } catch (Throwable error) {
                LOG.warn("Resolve mapper-location failed: {}", locationPattern, error);
                continue;
            }
            for (Resource resource : resources) {
                String namespace = extractMapperNamespace(resource);
                if (namespace == null) {
                    continue;
                }
                mapperTypes.add(namespace);
                int split = namespace.lastIndexOf('.');
                if (split > 0) {
                    packagePrefixes.add(namespace.substring(0, split));
                }
            }
        }

        if (!mapperTypes.isEmpty()) {
            emitDiagnostic("Mapper-locations resolved mapper types: " + mapperTypes.size() + " -> " + mapperTypes);
            LOG.info("Mapper-locations resolved mapper types: {}", mapperTypes);
        }
        return new MapperLocationDiscovery(mapperTypes, packagePrefixes);
    }

    private List<String> discoverMapperLocationsFromEnvironment(ConfigurableListableBeanFactory beanFactory) {
        if (beanFactory == null) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> locations = new LinkedHashSet<String>();
        try {
            Object environment = beanFactory.getBean("environment");
            if (environment == null) {
                return Collections.emptyList();
            }
            Method getProperty = environment.getClass().getMethod("getProperty", String.class);
            addMapperLocationPropertyValues(locations, getProperty.invoke(environment, "mybatis.mapper-locations"));
            addMapperLocationPropertyValues(locations, getProperty.invoke(environment, "mybatis-plus.mapper-locations"));
            addMapperLocationPropertyValues(locations, getProperty.invoke(environment, "mybatis.mapperLocations"));
        } catch (Throwable ignored) {
            // ignore environment resolution failure
        }
        if (locations.isEmpty()) {
            locations.addAll(discoverMapperLocationsFromConfigResources());
        }
        if (!locations.isEmpty()) {
            emitDiagnostic("Resolved mapper-locations from Spring Environment: " + locations);
            LOG.info("Resolved mapper-locations from Spring Environment: {}", locations);
        }
        return locations.isEmpty() ? Collections.<String>emptyList() : new ArrayList<String>(locations);
    }

    private void addMapperLocationPropertyValues(Set<String> output, Object rawValue) {
        if (output == null || rawValue == null) {
            return;
        }
        for (String split : splitLocationText(String.valueOf(rawValue))) {
            String clean = clean(split);
            if (clean != null && !clean.contains("${")) {
                output.add(clean);
            }
        }
    }

    private List<String> discoverMapperLocationsFromConfigResources() {
        LinkedHashSet<String> locations = new LinkedHashSet<String>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(
                Thread.currentThread().getContextClassLoader()
        );
        collectMapperLocationsFromYamlResources(resolver, "classpath*:application*.yml", locations);
        collectMapperLocationsFromYamlResources(resolver, "classpath*:application*.yaml", locations);
        collectMapperLocationsFromPropertiesResources(resolver, "classpath*:application*.properties", locations);
        if (!locations.isEmpty()) {
            emitDiagnostic("Resolved mapper-locations from config resources: " + locations);
            LOG.info("Resolved mapper-locations from config resources: {}", locations);
        }
        return locations.isEmpty() ? Collections.<String>emptyList() : new ArrayList<String>(locations);
    }

    private void collectMapperLocationsFromYamlResources(PathMatchingResourcePatternResolver resolver,
                                                         String pattern,
                                                         Set<String> output) {
        Resource[] resources;
        try {
            resources = resolver.getResources(pattern);
        } catch (IOException ignored) {
            return;
        }
        for (Resource resource : resources) {
            List<String> lines = readResourceLines(resource);
            if (lines.isEmpty()) {
                continue;
            }
            int mybatisIndent = -1;
            int mybatisPlusIndent = -1;
            for (String rawLine : lines) {
                if (rawLine == null) {
                    continue;
                }
                String line = stripLineComment(rawLine);
                if (line.trim().isEmpty()) {
                    continue;
                }
                int indent = countLeadingSpaces(line);
                if (mybatisIndent >= 0 && indent <= mybatisIndent && !line.trim().startsWith("mybatis:")) {
                    mybatisIndent = -1;
                }
                if (mybatisPlusIndent >= 0 && indent <= mybatisPlusIndent && !line.trim().startsWith("mybatis-plus:")) {
                    mybatisPlusIndent = -1;
                }
                if (line.trim().startsWith("mybatis:")) {
                    mybatisIndent = indent;
                    continue;
                }
                if (line.trim().startsWith("mybatis-plus:")) {
                    mybatisPlusIndent = indent;
                    continue;
                }
                Matcher flatMatcher = FLAT_MAPPER_LOCATION_PATTERN.matcher(line);
                if (flatMatcher.find()) {
                    addMapperLocationPropertyValues(output, flatMatcher.group(2));
                    continue;
                }
                Matcher nestedMatcher = YAML_MAPPER_LOCATION_PATTERN.matcher(line);
                if (!nestedMatcher.find()) {
                    continue;
                }
                if ((mybatisIndent >= 0 && indent > mybatisIndent)
                        || (mybatisPlusIndent >= 0 && indent > mybatisPlusIndent)) {
                    addMapperLocationPropertyValues(output, nestedMatcher.group(1));
                }
            }
        }
    }

    private void collectMapperLocationsFromPropertiesResources(PathMatchingResourcePatternResolver resolver,
                                                               String pattern,
                                                               Set<String> output) {
        Resource[] resources;
        try {
            resources = resolver.getResources(pattern);
        } catch (IOException ignored) {
            return;
        }
        for (Resource resource : resources) {
            if (resource == null || !resource.exists()) {
                continue;
            }
            Properties properties = new Properties();
            try {
                InputStream inputStream = resource.getInputStream();
                try {
                    properties.load(inputStream);
                } finally {
                    inputStream.close();
                }
            } catch (Throwable ignored) {
                continue;
            }
            addMapperLocationPropertyValues(output, properties.getProperty("mybatis.mapper-locations"));
            addMapperLocationPropertyValues(output, properties.getProperty("mybatis-plus.mapper-locations"));
            addMapperLocationPropertyValues(output, properties.getProperty("mybatis.mapperLocations"));
        }
    }

    private List<String> readResourceLines(Resource resource) {
        if (resource == null || !resource.exists()) {
            return Collections.emptyList();
        }
        ArrayList<String> lines = new ArrayList<String>();
        try {
            InputStream inputStream = resource.getInputStream();
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            } finally {
                inputStream.close();
            }
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
        return lines;
    }

    private String stripLineComment(String line) {
        if (line == null) {
            return "";
        }
        int commentIndex = line.indexOf('#');
        if (commentIndex < 0) {
            return line;
        }
        return line.substring(0, commentIndex);
    }

    private int countLeadingSpaces(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        while (count < text.length() && Character.isWhitespace(text.charAt(count))) {
            count++;
        }
        return count;
    }

    private List<String> discoverMybatisScanPackages(ConfigurableListableBeanFactory beanFactory,
                                                     List<String> beanNames) {
        if (beanNames == null || beanNames.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> packages = new LinkedHashSet<String>();
        for (String beanName : beanNames) {
            BeanDefinition definition = beanFactory.getBeanDefinition(beanName);
            String beanClassName = clean(definition.getBeanClassName());
            if (!isMapperScannerConfigurerDefinition(beanName, beanClassName, definition)) {
                continue;
            }
            PropertyValue basePackage = definition.getPropertyValues().getPropertyValue("basePackage");
            if (basePackage != null) {
                for (String split : splitLocationText(String.valueOf(basePackage.getValue()))) {
                    String clean = clean(split);
                    if (clean != null) {
                        packages.add(clean);
                    }
                }
            }
        }
        if (!packages.isEmpty()) {
            emitDiagnostic("Resolved mybatis scan packages from context: " + packages);
            LOG.info("Resolved mybatis scan packages from context: {}", packages);
        }
        return packages.isEmpty() ? Collections.<String>emptyList() : new ArrayList<String>(packages);
    }

    private boolean isMapperScannerConfigurerDefinition(String beanName,
                                                        String beanClassName,
                                                        BeanDefinition definition) {
        if ("org.mybatis.spring.mapper.MapperScannerConfigurer".equals(beanClassName)) {
            return true;
        }
        PropertyValue basePackage = definition.getPropertyValues().getPropertyValue("basePackage");
        if (basePackage == null) {
            return false;
        }
        String name = beanName == null ? "" : beanName.toLowerCase();
        String className = beanClassName == null ? "" : beanClassName.toLowerCase();
        return name.contains("mapperscanner")
                || className.contains("mapperscanner")
                || className.contains("scanner");
    }

    private void collectMapperLocationsFromBeanDefinitions(ConfigurableListableBeanFactory beanFactory,
                                                           List<String> beanNames,
                                                           Set<String> locationPatterns) {
        if (beanNames == null || beanNames.isEmpty()) {
            return;
        }
        for (String beanName : beanNames) {
            BeanDefinition definition = beanFactory.getBeanDefinition(beanName);
            PropertyValue mapperLocations = definition.getPropertyValues().getPropertyValue("mapperLocations");
            if (mapperLocations != null) {
                extractMapperLocationPatterns(mapperLocations.getValue(), locationPatterns);
            }
            PropertyValue mapperLocation = definition.getPropertyValues().getPropertyValue("mapperLocation");
            if (mapperLocation != null) {
                extractMapperLocationPatterns(mapperLocation.getValue(), locationPatterns);
            }
        }
    }

    private void extractMapperLocationPatterns(Object source, Set<String> output) {
        if (source == null || output == null) {
            return;
        }
        if (source instanceof String) {
            for (String item : splitLocationText((String) source)) {
                String clean = clean(item);
                if (clean != null && !clean.contains("${")) {
                    output.add(clean);
                }
            }
            return;
        }
        if (source instanceof String[]) {
            for (String item : (String[]) source) {
                extractMapperLocationPatterns(item, output);
            }
            return;
        }
        if (source instanceof Collection) {
            for (Object item : (Collection<?>) source) {
                extractMapperLocationPatterns(item, output);
            }
            return;
        }
        if (source.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(source);
            for (int index = 0; index < length; index++) {
                extractMapperLocationPatterns(java.lang.reflect.Array.get(source, index), output);
            }
            return;
        }
        if ("org.springframework.beans.factory.config.TypedStringValue".equals(source.getClass().getName())) {
            try {
                Method getValue = source.getClass().getMethod("getValue");
                Object value = getValue.invoke(source);
                extractMapperLocationPatterns(value, output);
                return;
            } catch (Throwable ignored) {
                // fallback below
            }
        }
        String text = clean(String.valueOf(source));
        if (text != null) {
            extractMapperLocationPatterns(text, output);
        }
    }

    private String extractMapperNamespace(Resource resource) {
        if (resource == null || !resource.exists()) {
            return null;
        }
        StringBuilder xml = new StringBuilder(4096);
        try {
            InputStream inputStream = resource.getInputStream();
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                String line;
                int total = 0;
                while ((line = reader.readLine()) != null) {
                    xml.append(line);
                    total += line.length();
                    if (total > 200000) {
                        break;
                    }
                }
            } finally {
                inputStream.close();
            }
        } catch (Throwable error) {
            LOG.debug("Read mapper xml failed: {}", resource, error);
            return null;
        }
        Matcher matcher = MAPPER_NAMESPACE_PATTERN.matcher(xml);
        if (!matcher.find()) {
            return null;
        }
        String namespace = clean(matcher.group(1));
        if (namespace == null || namespace.contains(" ")) {
            return null;
        }
        return namespace;
    }

    private void applyDefaultPlaceholderValues(ConfigurableListableBeanFactory beanFactory,
                                               List<String> beanNames) {
        if (beanNames == null || beanNames.isEmpty()) {
            return;
        }
        LinkedHashMap<String, String> defaultsByKey = new LinkedHashMap<String, String>();
        LinkedHashSet<String> inspectedTypes = new LinkedHashSet<String>();
        for (String beanName : beanNames) {
            BeanDefinition definition = beanFactory.getBeanDefinition(beanName);
            Class<?> beanType = resolveInspectableBeanType(beanName, definition);
            if (beanType == null) {
                continue;
            }
            if (!inspectedTypes.add(beanType.getName())) {
                continue;
            }
            collectPlaceholderDefaults(beanType, defaultsByKey);
        }
        if (defaultsByKey.isEmpty()) {
            return;
        }
        Properties defaults = new Properties();
        for (Map.Entry<String, String> entry : defaultsByKey.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            defaults.setProperty(entry.getKey(), entry.getValue());
        }
        if (defaults.isEmpty()) {
            return;
        }
        PropertySourcesPlaceholderConfigurer configurer = new PropertySourcesPlaceholderConfigurer();
        configurer.setLocalOverride(false);
        configurer.setIgnoreUnresolvablePlaceholders(true);
        configurer.setProperties(defaults);
        configurer.postProcessBeanFactory(beanFactory);
        emitDiagnostic("Applied placeholder defaults: count=" + defaults.size() + " keys=" + defaultsByKey.keySet());
        LOG.info("Applied placeholder defaults: count={} keys={}", defaults.size(), defaultsByKey.keySet());
    }

    private void collectPlaceholderDefaults(Class<?> beanType, Map<String, String> defaultsByKey) {
        if (beanType == null || defaultsByKey == null) {
            return;
        }
        Class<?> cursor = beanType;
        while (cursor != null && cursor != Object.class) {
            for (Field field : cursor.getDeclaredFields()) {
                Value value = field.getAnnotation(Value.class);
                if (value == null) {
                    continue;
                }
                registerPlaceholderDefault(value.value(), field.getType(), defaultsByKey);
            }
            cursor = cursor.getSuperclass();
        }
        Constructor<?>[] constructors = beanType.getDeclaredConstructors();
        for (Constructor<?> constructor : constructors) {
            Parameter[] parameters = constructor.getParameters();
            for (Parameter parameter : parameters) {
                Value value = parameter.getAnnotation(Value.class);
                if (value == null) {
                    continue;
                }
                registerPlaceholderDefault(value.value(), parameter.getType(), defaultsByKey);
            }
        }
        Method[] methods = beanType.getDeclaredMethods();
        for (Method method : methods) {
            Value methodValue = method.getAnnotation(Value.class);
            if (methodValue != null) {
                registerPlaceholderDefault(methodValue.value(), method.getReturnType(), defaultsByKey);
            }
            Parameter[] parameters = method.getParameters();
            for (Parameter parameter : parameters) {
                Value value = parameter.getAnnotation(Value.class);
                if (value == null) {
                    continue;
                }
                registerPlaceholderDefault(value.value(), parameter.getType(), defaultsByKey);
            }
        }
    }

    private void registerPlaceholderDefault(String expression,
                                            Class<?> targetType,
                                            Map<String, String> defaultsByKey) {
        if (expression == null || defaultsByKey == null) {
            return;
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(expression);
        while (matcher.find()) {
            String content = clean(matcher.group(1));
            if (content == null) {
                continue;
            }
            int split = content.indexOf(':');
            String key = split >= 0 ? content.substring(0, split) : content;
            key = clean(key);
            if (key == null || key.contains("#{")) {
                continue;
            }
            String inferredDefault = inferPlaceholderDefaultValue(targetType);
            String existing = defaultsByKey.get(key);
            if (existing == null) {
                defaultsByKey.put(key, inferredDefault);
                continue;
            }
            String preferred = preferPlaceholderDefault(existing, inferredDefault);
            defaultsByKey.put(key, preferred);
        }
    }

    private String inferPlaceholderDefaultValue(Class<?> targetType) {
        if (targetType == null) {
            return "";
        }
        if (String.class.equals(targetType) || CharSequence.class.isAssignableFrom(targetType)) {
            return "";
        }
        if (Boolean.TYPE.equals(targetType) || Boolean.class.equals(targetType)) {
            return "false";
        }
        if (Character.TYPE.equals(targetType) || Character.class.equals(targetType)) {
            return "0";
        }
        if (Number.class.isAssignableFrom(targetType)
                || Byte.TYPE.equals(targetType)
                || Short.TYPE.equals(targetType)
                || Integer.TYPE.equals(targetType)
                || Long.TYPE.equals(targetType)
                || Float.TYPE.equals(targetType)
                || Double.TYPE.equals(targetType)) {
            return "0";
        }
        return "";
    }

    private String preferPlaceholderDefault(String current, String candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        if (current.equals(candidate)) {
            return current;
        }
        if (current.isEmpty() && !candidate.isEmpty()) {
            return candidate;
        }
        return current;
    }

    private Map<String, String> inferExpectedTypesFromBeanDefinitions(ConfigurableListableBeanFactory beanFactory,
                                                                      List<String> beanNames) {
        if (beanNames == null || beanNames.isEmpty()) {
            return Collections.emptyMap();
        }
        LinkedHashMap<String, String> inferred = new LinkedHashMap<String, String>();
        LinkedHashSet<String> inspectedClasses = new LinkedHashSet<String>();
        for (String consumerBeanName : beanNames) {
            BeanDefinition definition = beanFactory.getBeanDefinition(consumerBeanName);
            Class<?> consumerType = resolveInspectableBeanType(consumerBeanName, definition);
            if (consumerType == null) {
                continue;
            }
            if (!inspectedClasses.add(consumerType.getName())) {
                continue;
            }
            collectExpectedTypesFromClass(beanFactory, consumerType, inferred);
        }
        if (!inferred.isEmpty()) {
            emitDiagnostic("Inferred expected type mappings from bean definitions: " + inferred.size() + " -> " + inferred);
            LOG.info("Inferred expected type mappings from bean definitions: {}", inferred);
        }
        return inferred;
    }

    private Set<String> inferRequiredDaoMapperTypesFromBeanDefinitions(ConfigurableListableBeanFactory beanFactory,
                                                                       List<String> beanNames) {
        if (beanNames == null || beanNames.isEmpty()) {
            return Collections.emptySet();
        }
        LinkedHashSet<String> requiredTypes = new LinkedHashSet<String>();
        LinkedHashSet<String> inspectedClasses = new LinkedHashSet<String>();
        for (String consumerBeanName : beanNames) {
            BeanDefinition definition = beanFactory.getBeanDefinition(consumerBeanName);
            Class<?> consumerType = resolveInspectableBeanType(consumerBeanName, definition);
            if (consumerType == null) {
                continue;
            }
            if (!inspectedClasses.add(consumerType.getName())) {
                continue;
            }
            collectRequiredDaoMapperTypesFromClass(consumerType, requiredTypes);
        }
        if (!requiredTypes.isEmpty()) {
            emitDiagnostic("Inferred required DAO/Mapper types: " + requiredTypes.size() + " -> " + requiredTypes);
            LOG.info("Inferred required DAO/Mapper types: {}", requiredTypes);
        }
        return requiredTypes;
    }

    private Class<?> resolveInspectableBeanType(String beanName, BeanDefinition definition) {
        LinkedHashSet<String> candidates = new LinkedHashSet<String>();
        addCandidate(candidates, clean(definition.getBeanClassName()));
        for (String candidate : collectCandidateTypeNames(beanName, definition)) {
            addCandidate(candidates, candidate);
        }
        for (String candidate : candidates) {
            String clean = clean(candidate);
            if (clean == null || looksLikeProxyTypeName(clean)) {
                continue;
            }
            if (isFrameworkType(clean) && !isBusinessPackage(clean)) {
                continue;
            }
            try {
                Class<?> type = Class.forName(clean, false, Thread.currentThread().getContextClassLoader());
                if (type.isInterface() || type.isAnnotation() || type.isEnum() || type.isPrimitive()) {
                    continue;
                }
                return type;
            } catch (Throwable ignored) {
                // continue next candidate
            }
        }
        return null;
    }

    private void collectRequiredDaoMapperTypesFromClass(Class<?> consumerType, Set<String> requiredTypes) {
        if (consumerType == null || requiredTypes == null) {
            return;
        }
        Class<?> cursor = consumerType;
        while (cursor != null && cursor != Object.class) {
            for (Field field : cursor.getDeclaredFields()) {
                Annotation[] annotations = field.getAnnotations();
                if (!hasAutowiredLikeAnnotation(annotations)
                        && resolveInjectionBeanName(annotations) == null
                        && resolveMethodLevelResourceBeanName(annotations) == null) {
                    continue;
                }
                registerRequiredDaoMapperType(field.getType(), requiredTypes);
            }
            cursor = cursor.getSuperclass();
        }

        Constructor<?>[] constructors = consumerType.getDeclaredConstructors();
        List<Constructor<?>> targetConstructors = selectInjectableConstructors(constructors);
        for (Constructor<?> constructor : targetConstructors) {
            for (Parameter parameter : constructor.getParameters()) {
                registerRequiredDaoMapperType(parameter.getType(), requiredTypes);
            }
        }

        for (Method method : consumerType.getDeclaredMethods()) {
            boolean methodAutowired = hasAutowiredLikeAnnotation(method.getAnnotations());
            String methodResourceBeanName = resolveMethodLevelResourceBeanName(method.getAnnotations());
            if (!methodAutowired && methodResourceBeanName == null) {
                continue;
            }
            for (Parameter parameter : method.getParameters()) {
                registerRequiredDaoMapperType(parameter.getType(), requiredTypes);
            }
        }
    }

    private void registerRequiredDaoMapperType(Class<?> type, Set<String> requiredTypes) {
        if (type == null || requiredTypes == null) {
            return;
        }
        String typeName = clean(type.getName());
        if (typeName == null || !isDaoOrMapperType(typeName)) {
            return;
        }
        requiredTypes.add(typeName);
    }

    private void collectExpectedTypesFromClass(ConfigurableListableBeanFactory beanFactory,
                                               Class<?> consumerType,
                                               Map<String, String> inferred) {
        Class<?> cursor = consumerType;
        while (cursor != null && cursor != Object.class) {
            for (Field field : cursor.getDeclaredFields()) {
                String targetBeanName = resolveInjectionBeanName(field.getAnnotations());
                if (targetBeanName == null) {
                    continue;
                }
                registerInferredExpectedType(beanFactory,
                        inferred,
                        targetBeanName,
                        field.getType().getName(),
                        consumerType.getName() + "#" + field.getName());
            }
            cursor = cursor.getSuperclass();
        }

        Constructor<?>[] constructors = consumerType.getDeclaredConstructors();
        List<Constructor<?>> targetConstructors = selectInjectableConstructors(constructors);
        for (Constructor<?> constructor : targetConstructors) {
            Parameter[] parameters = constructor.getParameters();
            Annotation[][] parameterAnnotations = constructor.getParameterAnnotations();
            for (int index = 0; index < parameters.length; index++) {
                Parameter parameter = parameters[index];
                String targetBeanName = resolveInjectionBeanName(parameterAnnotations[index]);
                if (targetBeanName == null && parameter.isNamePresent()) {
                    targetBeanName = clean(parameter.getName());
                }
                if (targetBeanName == null) {
                    continue;
                }
                registerInferredExpectedType(beanFactory,
                        inferred,
                        targetBeanName,
                        parameter.getType().getName(),
                        consumerType.getName() + "#<init>(" + index + ")");
            }
        }

        for (Method method : consumerType.getDeclaredMethods()) {
            boolean methodAutowired = hasAutowiredLikeAnnotation(method.getAnnotations());
            String methodResourceBeanName = resolveMethodLevelResourceBeanName(method.getAnnotations());
            Annotation[][] parameterAnnotations = method.getParameterAnnotations();
            Parameter[] parameters = method.getParameters();
            for (int index = 0; index < parameters.length; index++) {
                String targetBeanName = resolveInjectionBeanName(parameterAnnotations[index]);
                if (targetBeanName == null && methodResourceBeanName != null && parameters.length == 1) {
                    targetBeanName = methodResourceBeanName;
                }
                if (targetBeanName == null && methodAutowired && parameters[index].isNamePresent()) {
                    targetBeanName = clean(parameters[index].getName());
                }
                if (targetBeanName == null) {
                    continue;
                }
                registerInferredExpectedType(beanFactory,
                        inferred,
                        targetBeanName,
                        parameters[index].getType().getName(),
                        consumerType.getName() + "#" + method.getName() + "(" + index + ")");
            }
        }
    }

    private List<Constructor<?>> selectInjectableConstructors(Constructor<?>[] constructors) {
        if (constructors == null || constructors.length == 0) {
            return Collections.emptyList();
        }
        List<Constructor<?>> annotated = new ArrayList<Constructor<?>>();
        for (Constructor<?> constructor : constructors) {
            if (hasAutowiredLikeAnnotation(constructor.getAnnotations())) {
                annotated.add(constructor);
            }
        }
        if (!annotated.isEmpty()) {
            return annotated;
        }
        if (constructors.length == 1) {
            return Collections.singletonList(constructors[0]);
        }
        return Collections.emptyList();
    }

    private boolean hasAutowiredLikeAnnotation(Annotation[] annotations) {
        if (annotations == null) {
            return false;
        }
        for (Annotation annotation : annotations) {
            if (annotation == null) {
                continue;
            }
            String annotationName = annotation.annotationType().getName();
            if ("org.springframework.beans.factory.annotation.Autowired".equals(annotationName)
                    || "javax.annotation.Resource".equals(annotationName)
                    || "jakarta.annotation.Resource".equals(annotationName)) {
                return true;
            }
        }
        return false;
    }

    private String resolveInjectionBeanName(Annotation[] annotations) {
        if (annotations == null || annotations.length == 0) {
            return null;
        }
        for (Annotation annotation : annotations) {
            if (annotation == null) {
                continue;
            }
            String annotationName = annotation.annotationType().getName();
            if ("org.springframework.beans.factory.annotation.Qualifier".equals(annotationName)
                    || "javax.inject.Named".equals(annotationName)
                    || "jakarta.inject.Named".equals(annotationName)) {
                String value = invokeAnnotationStringAttribute(annotation, "value");
                if (value != null) {
                    return value;
                }
            }
            if ("javax.annotation.Resource".equals(annotationName)
                    || "jakarta.annotation.Resource".equals(annotationName)) {
                String name = invokeAnnotationStringAttribute(annotation, "name");
                if (name != null) {
                    return name;
                }
            }
        }
        return null;
    }

    private String resolveMethodLevelResourceBeanName(Annotation[] annotations) {
        if (annotations == null || annotations.length == 0) {
            return null;
        }
        for (Annotation annotation : annotations) {
            if (annotation == null) {
                continue;
            }
            String annotationName = annotation.annotationType().getName();
            if (!"javax.annotation.Resource".equals(annotationName)
                    && !"jakarta.annotation.Resource".equals(annotationName)) {
                continue;
            }
            String name = invokeAnnotationStringAttribute(annotation, "name");
            if (name != null) {
                return name;
            }
        }
        return null;
    }

    private String invokeAnnotationStringAttribute(Annotation annotation, String attributeName) {
        if (annotation == null || attributeName == null) {
            return null;
        }
        try {
            Method method = annotation.annotationType().getMethod(attributeName);
            Object value = method.invoke(annotation);
            if (value == null) {
                return null;
            }
            String text = String.valueOf(value).trim();
            return text.isEmpty() ? null : text;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void registerInferredExpectedType(ConfigurableListableBeanFactory beanFactory,
                                              Map<String, String> inferred,
                                              String beanName,
                                              String expectedType,
                                              String source) {
        String cleanBeanName = clean(beanName);
        String cleanExpectedType = clean(expectedType);
        if (cleanBeanName == null || cleanExpectedType == null) {
            return;
        }
        String existing = inferred.get(cleanBeanName);
        if (existing == null) {
            inferred.put(cleanBeanName, cleanExpectedType);
            return;
        }
        if (existing.equals(cleanExpectedType)) {
            return;
        }
        String preferred = preferExpectedType(existing, cleanExpectedType);
        if (!preferred.equals(existing)) {
            inferred.put(cleanBeanName, preferred);
            LOG.info("Update inferred expected type bean='{}' {} -> {} source={}",
                    cleanBeanName,
                    existing,
                    preferred,
                    source);
        }
    }

    private String preferExpectedType(String current, String candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        if (current.equals(candidate)) {
            return current;
        }
        int currentScore = expectedTypeScore(current);
        int candidateScore = expectedTypeScore(candidate);
        if (candidateScore > currentScore) {
            return candidate;
        }
        return current;
    }

    private int expectedTypeScore(String typeName) {
        String clean = clean(typeName);
        if (clean == null) {
            return 0;
        }
        int score = 0;
        if (!looksLikeProxyTypeName(clean)) {
            score += 2;
        }
        if (isBusinessPackage(clean)) {
            score += 2;
        }
        if (isInterfaceTypeStrict(clean) || clean.endsWith("$Iface")) {
            score += 3;
        }
        if (!isFrameworkType(clean)) {
            score += 1;
        }
        return score;
    }

    private String findMockReason(String beanName, MockTarget target) {
        String matchedPrefix = findMatchingPrefix(target.typeName, forceMockTypePrefixes);
        if (matchedPrefix != null) {
            return "force-mock-class-prefix(" + matchedPrefix + ")";
        }
        if (isForceMockType(beanName, target)) {
            return "force-mock-external-type";
        }
        if (isMapperLocationType(target.typeName)) {
            return "mybatis-mapper-location";
        }
        if (isMybatisScannedType(target.typeName)) {
            return "mybatis-scan-package";
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
        if (hasAnySuffixIgnoreCase(target.typeName, daoMapperSuffixes)) {
            return "dao-mapper-class-suffix";
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
        String thriftIfaceType = resolveThriftIfaceType(serviceInterfaceType);
        if (thriftIfaceType != null) {
            if (!thriftIfaceType.equals(serviceInterfaceType)) {
                emitDiagnostic("Thrift serviceInterface refined to Iface: " + serviceInterfaceType + " -> " + thriftIfaceType);
                LOG.info("Thrift serviceInterface refined to Iface: {} -> {}", serviceInterfaceType, thriftIfaceType);
            }
            return thriftIfaceType;
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

    private boolean isInterfaceTypeStrict(String typeName) {
        String clean = clean(typeName);
        if (clean == null) {
            return false;
        }
        try {
            Class<?> type = Class.forName(clean, false, Thread.currentThread().getContextClassLoader());
            return type.isInterface();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String resolveThriftIfaceType(String serviceInterfaceType) {
        String clean = clean(serviceInterfaceType);
        if (clean == null) {
            return null;
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<String>();
        candidates.add(clean);
        if (clean.endsWith(".Iface")) {
            candidates.add(clean.substring(0, clean.length() - ".Iface".length()) + "$Iface");
        }
        if (!clean.endsWith("$Iface") && !clean.endsWith(".Iface")) {
            candidates.add(clean + "$Iface");
            candidates.add(clean + ".Iface");
        }
        for (String candidate : candidates) {
            String normalized = normalizeDottedNestedTypeName(candidate);
            if (normalized != null && isInterfaceTypeStrict(normalized)) {
                return normalized;
            }
        }
        return null;
    }

    private String normalizeDottedNestedTypeName(String typeName) {
        String clean = clean(typeName);
        if (clean == null) {
            return null;
        }
        if (isInterfaceTypeStrict(clean)) {
            return clean;
        }
        int packageDot = clean.lastIndexOf('.');
        if (packageDot <= 0 || packageDot >= clean.length() - 1) {
            return clean;
        }
        String nested = clean.substring(0, packageDot) + "$" + clean.substring(packageDot + 1);
        if (isInterfaceTypeStrict(nested)) {
            return nested;
        }
        return clean;
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
        if (packagePrefixes.isEmpty()
                && mapperLocationPackagePrefixes.isEmpty()
                && mybatisScanPackagePrefixes.isEmpty()) {
            return true;
        }
        for (String prefix : packagePrefixes) {
            if (className.equals(prefix) || className.startsWith(prefix + ".")) {
                return true;
            }
        }
        for (String prefix : mapperLocationPackagePrefixes) {
            if (className.equals(prefix) || className.startsWith(prefix + ".")) {
                return true;
            }
        }
        for (String prefix : mybatisScanPackagePrefixes) {
            if (className.equals(prefix) || className.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    private boolean isMapperLocationType(String className) {
        String clean = clean(className);
        if (clean == null || mapperLocationTypeNames.isEmpty()) {
            return false;
        }
        if (mapperLocationTypeNames.contains(clean)) {
            return true;
        }
        String normalized = normalizeCglibTypeName(clean);
        return normalized != null && mapperLocationTypeNames.contains(normalized);
    }

    private boolean isMybatisScannedType(String className) {
        String clean = clean(className);
        if (clean == null || mybatisScanPackagePrefixes.isEmpty()) {
            return false;
        }
        for (String prefix : mybatisScanPackagePrefixes) {
            if (clean.equals(prefix) || clean.startsWith(prefix + ".")) {
                return true;
            }
        }
        String normalized = normalizeCglibTypeName(clean);
        if (normalized == null) {
            return false;
        }
        for (String prefix : mybatisScanPackagePrefixes) {
            if (normalized.equals(prefix) || normalized.startsWith(prefix + ".")) {
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

    private boolean hasAnySuffixIgnoreCase(String value, Set<String> suffixes) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) {
            return false;
        }
        String lowerValue = clean.toLowerCase();
        for (String suffix : suffixes) {
            if (suffix == null) {
                continue;
            }
            String trimmed = suffix.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (lowerValue.endsWith(trimmed.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private boolean isDaoOrMapperType(String typeName) {
        return hasAnySuffixIgnoreCase(typeName, daoMapperSuffixes);
    }

    private boolean containsBeanName(Set<String> names, String beanName) {
        if (names == null || names.isEmpty()) {
            return false;
        }
        String cleanBeanName = clean(beanName);
        if (cleanBeanName == null) {
            return false;
        }
        for (String candidate : beanNameCandidates(cleanBeanName)) {
            if (names.contains(candidate)) {
                return true;
            }
        }
        for (String existing : names) {
            if (existing == null) {
                continue;
            }
            if (cleanBeanName.equalsIgnoreCase(existing.trim())) {
                return true;
            }
        }
        return false;
    }

    private String findValueByBeanName(Map<String, String> source, String beanName) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        String cleanBeanName = clean(beanName);
        if (cleanBeanName == null) {
            return null;
        }
        for (String candidate : beanNameCandidates(cleanBeanName)) {
            String value = source.get(candidate);
            if (clean(value) != null) {
                return value;
            }
        }
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            if (cleanBeanName.equalsIgnoreCase(entry.getKey().trim())) {
                String value = clean(entry.getValue());
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private List<String> beanNameCandidates(String beanName) {
        String cleanBeanName = clean(beanName);
        if (cleanBeanName == null) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<String>();
        candidates.add(cleanBeanName);
        candidates.add(Introspector.decapitalize(cleanBeanName));
        if (!cleanBeanName.isEmpty()) {
            candidates.add(Character.toLowerCase(cleanBeanName.charAt(0)) + cleanBeanName.substring(1));
            candidates.add(Character.toUpperCase(cleanBeanName.charAt(0)) + cleanBeanName.substring(1));
        }
        return new ArrayList<String>(candidates);
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
        List<String> candidates = collectCandidateTypeNames(beanName, definition);
        String preferred = chooseBestMockTargetType(candidates);
        if (preferred != null) {
            return new MockTarget(preferred, false);
        }
        return null;
    }

    private String chooseBestMockTargetType(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        String businessInterface = null;
        String anyInterface = null;
        String businessClass = null;
        String anyClass = null;
        String proxyFallback = null;
        for (String candidate : candidates) {
            String clean = clean(candidate);
            if (clean == null) {
                continue;
            }
            if (looksLikeProxyTypeName(clean)) {
                if (proxyFallback == null) {
                    proxyFallback = clean;
                }
                continue;
            }
            boolean interfaceType = isInterfaceTypeStrict(clean) || clean.endsWith("$Iface");
            if (interfaceType) {
                if (isBusinessPackage(clean) && businessInterface == null) {
                    businessInterface = clean;
                }
                if (anyInterface == null) {
                    anyInterface = clean;
                }
                continue;
            }
            if (isBusinessPackage(clean) && businessClass == null) {
                businessClass = clean;
            }
            if (anyClass == null) {
                anyClass = clean;
            }
        }
        if (businessInterface != null) {
            return businessInterface;
        }
        if (anyInterface != null) {
            return anyInterface;
        }
        if (businessClass != null) {
            return businessClass;
        }
        if (anyClass != null) {
            return anyClass;
        }
        return proxyFallback != null ? proxyFallback : candidates.get(0);
    }

    private List<String> collectCandidateTypeNames(String beanName,
                                                   BeanDefinition definition) {
        LinkedHashSet<String> candidates = new LinkedHashSet<String>();

        String mapperType = resolveMapperInterfaceType(definition);
        addCandidate(candidates, mapperType);
        addCandidate(candidates, resolveServiceInterfaceType(definition));
        addCandidate(candidates, resolveNoOpTargetType(definition));

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

    private String resolveNoOpTargetType(BeanDefinition definition) {
        PropertyValue targetTypeName = definition.getPropertyValues().getPropertyValue("targetTypeName");
        if (targetTypeName == null) {
            return null;
        }
        return toLooseTypeName(targetTypeName.getValue());
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

    private static List<String> normalizeMapperLocations(List<String> mapperLocations) {
        if (mapperLocations == null || mapperLocations.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<String>();
        for (String mapperLocation : mapperLocations) {
            for (String split : splitLocationText(mapperLocation)) {
                String clean = split == null ? null : split.trim();
                if (clean != null && !clean.isEmpty()) {
                    normalized.add(clean);
                }
            }
        }
        return normalized.isEmpty()
                ? Collections.<String>emptyList()
                : new ArrayList<String>(normalized);
    }

    private static List<String> splitLocationText(String text) {
        if (text == null) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<String>();
        String[] commaSplit = text.split(",");
        for (String part : commaSplit) {
            if (part == null) {
                continue;
            }
            String[] semicolonSplit = part.split(";");
            for (String item : semicolonSplit) {
                if (item == null) {
                    continue;
                }
                String clean = item.trim();
                if (!clean.isEmpty()) {
                    values.add(clean);
                }
            }
        }
        return values;
    }

    private static Map<String, String> normalizeForceMockBeanTargetTypes(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        LinkedHashMap<String, String> normalized = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (entry == null) {
                continue;
            }
            String beanName = entry.getKey() == null ? null : entry.getKey().trim();
            String targetType = entry.getValue() == null ? null : entry.getValue().trim();
            if (beanName == null || beanName.isEmpty() || targetType == null || targetType.isEmpty()) {
                continue;
            }
            normalized.put(beanName, targetType);
        }
        return normalized.isEmpty()
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(normalized);
    }

    private static final class MapperLocationDiscovery {
        private final Set<String> mapperTypeNames;
        private final List<String> packagePrefixes;

        private MapperLocationDiscovery(Set<String> mapperTypeNames, Set<String> packagePrefixes) {
            this.mapperTypeNames = mapperTypeNames == null || mapperTypeNames.isEmpty()
                    ? Collections.<String>emptySet()
                    : Collections.unmodifiableSet(new LinkedHashSet<String>(mapperTypeNames));
            this.packagePrefixes = packagePrefixes == null || packagePrefixes.isEmpty()
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(packagePrefixes));
        }

        private static MapperLocationDiscovery empty() {
            return new MapperLocationDiscovery(Collections.<String>emptySet(), Collections.<String>emptySet());
        }
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
