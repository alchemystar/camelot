package com.camelot.analyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SpringCallPathAnalyzer {
    private static final Set<String> COMPONENT_ANNOTATIONS = setOf(
            "Component", "Service", "Repository", "Controller", "RestController", "Configuration"
    );
    private static final Set<String> INJECTION_ANNOTATIONS = setOf("Autowired", "Inject", "Resource");
    private static final Set<String> REQUEST_MAPPING_ANNOTATIONS = setOf(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping"
    );

    public static void main(String[] args) throws Exception {
        CliOptions options = CliOptions.parse(args);
        SpringCallPathAnalyzer analyzer = new SpringCallPathAnalyzer();
        AnalysisReport report = analyzer.analyze(options.projectRoot, options.maxDepth, options.maxPathsPerEndpoint);

        Files.createDirectories(options.outputDir);
        Path jsonPath = options.outputDir.resolve("analysis-report.json");
        Path dotPath = options.outputDir.resolve("call-graph.dot");

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(jsonPath.toFile(), report);
        Files.write(dotPath, report.toDot().getBytes(StandardCharsets.UTF_8));

        System.out.println("Analysis finished.");
        System.out.println("JSON report: " + jsonPath.toAbsolutePath());
        System.out.println("DOT graph:   " + dotPath.toAbsolutePath());
        System.out.println("Endpoints:   " + report.endpoints.size());
        System.out.println("Methods:     " + report.methodDisplay.size());
        System.out.println("Edges:       " + report.edges.size());
    }

    public AnalysisReport analyze(Path projectRoot, int maxDepth, int maxPathsPerEndpoint) throws IOException {
        JavaModel javaModel = parseJava(projectRoot);
        XmlModel xmlModel = parseXml(projectRoot);

        BeanRegistry beanRegistry = buildBeanRegistry(javaModel, xmlModel);
        InjectionRegistry injectionRegistry = buildInjectionRegistry(javaModel, xmlModel, beanRegistry);
        Map<String, MethodModel> methodsById = collectMethods(javaModel);
        List<CallEdge> edges = buildCallEdges(javaModel, methodsById, injectionRegistry);
        List<Endpoint> endpoints = buildEndpoints(javaModel, xmlModel, beanRegistry);
        Map<String, List<CallEdge>> edgesFrom = edges.stream().collect(Collectors.groupingBy(e -> e.from, LinkedHashMap::new, Collectors.toList()));

        List<EndpointPaths> endpointPaths = new ArrayList<>();
        for (Endpoint endpoint : endpoints) {
            List<List<String>> paths = enumeratePaths(endpoint.entryMethodId, edgesFrom, maxDepth, maxPathsPerEndpoint);
            endpointPaths.add(new EndpointPaths(endpoint.path, endpoint.httpMethods, endpoint.source, endpoint.entryMethodId, paths));
        }

        Map<String, String> methodDisplay = methodsById.values().stream()
                .collect(Collectors.toMap(m -> m.id, MethodModel::display, (a, b) -> a, LinkedHashMap::new));

        return new AnalysisReport(
                Instant.now().toString(),
                projectRoot.toAbsolutePath().toString(),
                methodDisplay,
                edges,
                endpointPaths
        );
    }

    private JavaModel parseJava(Path projectRoot) throws IOException {
        JavaModel model = new JavaModel();
        JavaParser parser = new JavaParser();

        List<Path> javaFiles;
        try (Stream<Path> stream = Files.walk(projectRoot)) {
            javaFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .collect(Collectors.toList());
        }

        for (Path javaFile : javaFiles) {
            ParseResult<CompilationUnit> result = parser.parse(javaFile);
            if (!result.getResult().isPresent()) {
                continue;
            }
            CompilationUnit cu = result.getResult().get();
            String packageName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
            ImportContext importContext = ImportContext.from(cu, packageName);

            for (TypeDeclaration<?> typeDeclaration : cu.getTypes()) {
                if (!(typeDeclaration instanceof ClassOrInterfaceDeclaration)) {
                    continue;
                }
                ClassOrInterfaceDeclaration declaration = (ClassOrInterfaceDeclaration) typeDeclaration;
                String simpleName = declaration.getNameAsString();
                String fqcn = isBlank(packageName) ? simpleName : packageName + "." + simpleName;

                ClassModel classModel = new ClassModel(fqcn, simpleName, packageName, importContext);
                classModel.annotations.addAll(annotationNames(declaration));
                classModel.implementedTypes.addAll(declaration.getImplementedTypes().stream().map(t -> t.asString()).collect(Collectors.toList()));
                declaration.getExtendedTypes().stream().findFirst().ifPresent(t -> classModel.superType = t.asString());
                classModel.beanNameFromAnnotation = extractBeanNameFromStereotype(declaration).orElse(null);
                classModel.classRequestPaths.addAll(extractPathsFromAnnotations(declaration.getAnnotations()));
                classModel.classHttpMethods.addAll(extractHttpMethods(declaration.getAnnotations()));

                for (FieldDeclaration fieldDeclaration : declaration.getFields()) {
                    Set<String> ann = annotationNames(fieldDeclaration);
                    String resourceName = extractResourceName(fieldDeclaration.getAnnotations()).orElse(null);
                    for (VariableDeclarator variable : fieldDeclaration.getVariables()) {
                        FieldModel fieldModel = new FieldModel(variable.getNameAsString(), variable.getType().asString(), ann, resourceName);
                        classModel.fields.put(fieldModel.name, fieldModel);
                    }
                }

                for (ConstructorDeclaration constructor : declaration.getConstructors()) {
                    boolean autowiredCtor = hasAnyAnnotation(constructor, setOf("Autowired", "Inject"));
                    if (!autowiredCtor && declaration.getConstructors().size() != 1) {
                        continue;
                    }
                    Map<String, String> paramTypes = constructor.getParameters().stream()
                            .collect(Collectors.toMap(Parameter::getNameAsString, p -> p.getType().asString(), (a, b) -> a));
                    for (AssignExpr assignExpr : constructor.findAll(AssignExpr.class)) {
                        if (!(assignExpr.getTarget() instanceof FieldAccessExpr)) {
                            continue;
                        }
                        FieldAccessExpr fa = (FieldAccessExpr) assignExpr.getTarget();
                        if (!(fa.getScope() instanceof ThisExpr)) {
                            continue;
                        }
                        if (!(assignExpr.getValue() instanceof NameExpr)) {
                            continue;
                        }
                        NameExpr ne = (NameExpr) assignExpr.getValue();
                        String paramType = paramTypes.get(ne.getNameAsString());
                        if (paramType != null) {
                            classModel.constructorInjectedFieldTypes.put(fa.getNameAsString(), paramType);
                        }
                    }
                }

                for (MethodDeclaration methodDeclaration : declaration.getMethods()) {
                    MethodModel methodModel = new MethodModel(
                            methodId(fqcn, methodDeclaration.getNameAsString(), methodDeclaration.getParameters().size()),
                            fqcn,
                            methodDeclaration.getNameAsString(),
                            methodDeclaration.getParameters().size(),
                            methodDeclaration.getBegin().map(p -> p.line).orElse(-1),
                            methodDeclaration.getType().asString()
                    );
                    methodModel.annotations.addAll(annotationNames(methodDeclaration));
                    methodModel.requestPaths.addAll(extractPathsFromAnnotations(methodDeclaration.getAnnotations()));
                    methodModel.httpMethods.addAll(extractHttpMethods(methodDeclaration.getAnnotations()));

                    for (Parameter parameter : methodDeclaration.getParameters()) {
                        methodModel.visibleVariableTypes.put(parameter.getNameAsString(), parameter.getType().asString());
                    }
                    for (VariableDeclarator variable : methodDeclaration.findAll(VariableDeclarator.class)) {
                        methodModel.visibleVariableTypes.put(variable.getNameAsString(), variable.getType().asString());
                    }

                    for (MethodCallExpr callExpr : methodDeclaration.findAll(MethodCallExpr.class)) {
                        methodModel.calls.add(toCallSite(callExpr));
                    }

                    classModel.methodsById.put(methodModel.id, methodModel);
                    classModel.methodsByName.computeIfAbsent(methodModel.name, k -> new ArrayList<>()).add(methodModel);
                }

                model.classesByName.put(fqcn, classModel);
                model.simpleToFqcn.computeIfAbsent(simpleName, k -> new LinkedHashSet<>()).add(fqcn);
            }
        }

        return model;
    }

    private CallSite toCallSite(MethodCallExpr callExpr) {
        ScopeType scopeType = ScopeType.OTHER;
        String scopeToken = null;
        CallSite scopeCall = null;

        if (!callExpr.getScope().isPresent()) {
            scopeType = ScopeType.UNSCOPED;
        } else {
            Expression scope = callExpr.getScope().get();
            if (scope instanceof ThisExpr) {
                scopeType = ScopeType.THIS;
            } else if (scope instanceof NameExpr) {
                NameExpr nameExpr = (NameExpr) scope;
                scopeType = ScopeType.NAME;
                scopeToken = nameExpr.getNameAsString();
            } else if (scope instanceof FieldAccessExpr && ((FieldAccessExpr) scope).getScope() instanceof ThisExpr) {
                FieldAccessExpr fieldAccessExpr = (FieldAccessExpr) scope;
                scopeType = ScopeType.NAME;
                scopeToken = fieldAccessExpr.getNameAsString();
            } else if (scope instanceof MethodCallExpr) {
                MethodCallExpr nestedCallExpr = (MethodCallExpr) scope;
                scopeType = ScopeType.METHOD_CALL;
                scopeCall = toCallSite(nestedCallExpr);
            }
        }

        int line = callExpr.getBegin().map(p -> p.line).orElse(-1);
        return new CallSite(scopeType, scopeToken, scopeCall, callExpr.getNameAsString(), callExpr.getArguments().size(), line);
    }

    private XmlModel parseXml(Path projectRoot) throws IOException {
        XmlModel model = new XmlModel();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);

        List<Path> xmlFiles;
        try (Stream<Path> stream = Files.walk(projectRoot)) {
            xmlFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".xml"))
                    .sorted()
                    .collect(Collectors.toList());
        }

        for (Path xml : xmlFiles) {
            Document doc;
            try {
                doc = factory.newDocumentBuilder().parse(xml.toFile());
            } catch (Exception e) {
                continue;
            }

            NodeList aliases = doc.getElementsByTagName("alias");
            for (int i = 0; i < aliases.getLength(); i++) {
                if (!(aliases.item(i) instanceof Element)) {
                    continue;
                }
                Element alias = (Element) aliases.item(i);
                String name = alias.getAttribute("name");
                String aliasName = alias.getAttribute("alias");
                if (!isBlank(name) && !isBlank(aliasName)) {
                    model.aliasToName.put(aliasName, name);
                }
            }

            NodeList beans = doc.getElementsByTagName("bean");
            for (int i = 0; i < beans.getLength(); i++) {
                if (!(beans.item(i) instanceof Element)) {
                    continue;
                }
                Element bean = (Element) beans.item(i);
                String beanId = bean.getAttribute("id");
                if (isBlank(beanId)) {
                    String nameAttr = bean.getAttribute("name");
                    if (!isBlank(nameAttr)) {
                        beanId = nameAttr.split("[,;\\s]")[0];
                    }
                }
                String className = bean.getAttribute("class");
                if (!isBlank(beanId) && !isBlank(className)) {
                    model.beansById.put(beanId, className);
                }

                String classLower = className.toLowerCase(Locale.ROOT);
                if (classLower.contains("simpleurlhandlermapping")) {
                    model.urlMappings.addAll(parseUrlMappings(bean));
                }

                NodeList properties = bean.getElementsByTagName("property");
                for (int j = 0; j < properties.getLength(); j++) {
                    if (!(properties.item(j) instanceof Element)) {
                        continue;
                    }
                    Element property = (Element) properties.item(j);
                    String name = property.getAttribute("name");
                    String ref = property.getAttribute("ref");
                    if (isBlank(ref)) {
                        NodeList refs = property.getElementsByTagName("ref");
                        for (int k = 0; k < refs.getLength(); k++) {
                            if (!(refs.item(k) instanceof Element)) {
                                continue;
                            }
                            Element refElement = (Element) refs.item(k);
                            ref = refElement.getAttribute("bean");
                            if (isBlank(ref)) {
                                ref = refElement.getAttribute("local");
                            }
                            if (!isBlank(ref)) {
                                break;
                            }
                        }
                    }
                    if (!isBlank(beanId) && !isBlank(name) && !isBlank(ref)) {
                        model.propertyRefs.add(new XmlPropertyRef(beanId, name, ref));
                    }
                }

                NodeList constructorArgs = bean.getElementsByTagName("constructor-arg");
                for (int j = 0; j < constructorArgs.getLength(); j++) {
                    if (!(constructorArgs.item(j) instanceof Element)) {
                        continue;
                    }
                    Element ctorArg = (Element) constructorArgs.item(j);
                    String ref = ctorArg.getAttribute("ref");
                    if (isBlank(ref)) {
                        continue;
                    }
                    String name = ctorArg.getAttribute("name");
                    if (isBlank(name)) {
                        name = "$ctor$" + ctorArg.getAttribute("index");
                    }
                    if (!isBlank(beanId)) {
                        model.propertyRefs.add(new XmlPropertyRef(beanId, name, ref));
                    }
                }
            }
        }

        return model;
    }

    private List<XmlUrlMapping> parseUrlMappings(Element mappingBean) {
        List<XmlUrlMapping> mappings = new ArrayList<>();
        NodeList properties = mappingBean.getElementsByTagName("property");
        for (int i = 0; i < properties.getLength(); i++) {
            if (!(properties.item(i) instanceof Element)) {
                continue;
            }
            Element property = (Element) properties.item(i);
            if (!"urlMap".equals(property.getAttribute("name"))) {
                continue;
            }

            NodeList entries = property.getElementsByTagName("entry");
            for (int j = 0; j < entries.getLength(); j++) {
                if (!(entries.item(j) instanceof Element)) {
                    continue;
                }
                Element entry = (Element) entries.item(j);
                String path = entry.getAttribute("key");
                String beanRef = entry.getAttribute("value-ref");
                if (!isBlank(path) && !isBlank(beanRef)) {
                    mappings.add(new XmlUrlMapping(path, beanRef));
                }
            }

            NodeList props = property.getElementsByTagName("prop");
            for (int j = 0; j < props.getLength(); j++) {
                if (!(props.item(j) instanceof Element)) {
                    continue;
                }
                Element prop = (Element) props.item(j);
                String path = prop.getAttribute("key");
                String beanRef = prop.getTextContent() == null ? "" : prop.getTextContent().trim();
                if (!isBlank(path) && !isBlank(beanRef)) {
                    mappings.add(new XmlUrlMapping(path, beanRef));
                }
            }
        }
        return mappings;
    }

    private BeanRegistry buildBeanRegistry(JavaModel javaModel, XmlModel xmlModel) {
        BeanRegistry registry = new BeanRegistry();

        for (ClassModel classModel : javaModel.classesByName.values()) {
            if (classModel.isComponent()) {
                String beanId = classModel.beanNameFromAnnotation;
                if (beanId == null || isBlank(beanId)) {
                    beanId = decapitalize(classModel.simpleName);
                }
                registry.beanIdToClasses.computeIfAbsent(beanId, k -> new LinkedHashSet<>()).add(classModel.fqcn);
            }
        }

        for (Map.Entry<String, String> entry : xmlModel.beansById.entrySet()) {
            String beanId = entry.getKey();
            String className = entry.getValue();
            registry.beanIdToClasses.computeIfAbsent(beanId, k -> new LinkedHashSet<>()).add(className);
        }

        registry.aliasToName.putAll(xmlModel.aliasToName);
        return registry;
    }

    private InjectionRegistry buildInjectionRegistry(JavaModel javaModel, XmlModel xmlModel, BeanRegistry beanRegistry) {
        InjectionRegistry registry = new InjectionRegistry();

        for (ClassModel classModel : javaModel.classesByName.values()) {
            Map<String, TargetSet> targetsByField = registry.targetsByClass.computeIfAbsent(classModel.fqcn, k -> new LinkedHashMap<>());
            for (FieldModel fieldModel : classModel.fields.values()) {
                boolean isInjectionPoint = !Collections.disjoint(fieldModel.annotations, INJECTION_ANNOTATIONS);
                boolean isResource = fieldModel.annotations.contains("Resource");

                if (isResource) {
                    String resourceName = fieldModel.resourceName;
                    if (resourceName == null || isBlank(resourceName)) {
                        resourceName = fieldModel.name;
                    }
                    Set<String> byBean = resolveClassesByBeanId(resourceName, beanRegistry, javaModel);
                    if (!byBean.isEmpty()) {
                        targetsByField.computeIfAbsent(fieldModel.name, k -> new TargetSet()).addAll(byBean, "XML/ANNOTATION:resource");
                    }
                }

                if (isInjectionPoint) {
                    Set<String> byType = resolveClassesByType(fieldModel.typeName, javaModel);
                    if (!byType.isEmpty()) {
                        targetsByField.computeIfAbsent(fieldModel.name, k -> new TargetSet()).addAll(byType, "ANNOTATION:type");
                    }
                }
            }

            for (Map.Entry<String, String> ctorEntry : classModel.constructorInjectedFieldTypes.entrySet()) {
                Set<String> byType = resolveClassesByType(ctorEntry.getValue(), javaModel);
                if (!byType.isEmpty()) {
                    targetsByField.computeIfAbsent(ctorEntry.getKey(), k -> new TargetSet()).addAll(byType, "ANNOTATION:ctor");
                }
            }
        }

        for (XmlPropertyRef propertyRef : xmlModel.propertyRefs) {
            String fromBean = xmlModel.resolveAlias(propertyRef.fromBeanId);
            String toBean = xmlModel.resolveAlias(propertyRef.toBeanId);

            Set<String> fromClasses = resolveClassesByBeanId(fromBean, beanRegistry, javaModel);
            Set<String> toClasses = resolveClassesByBeanId(toBean, beanRegistry, javaModel);
            if (fromClasses.isEmpty() || toClasses.isEmpty()) {
                continue;
            }
            if (propertyRef.propertyName.startsWith("$ctor$")) {
                continue;
            }

            for (String fromClass : fromClasses) {
                Map<String, TargetSet> targetsByField = registry.targetsByClass.computeIfAbsent(fromClass, k -> new LinkedHashMap<>());
                targetsByField.computeIfAbsent(propertyRef.propertyName, k -> new TargetSet()).addAll(toClasses, "XML:property-ref");
            }
        }

        return registry;
    }

    private Map<String, MethodModel> collectMethods(JavaModel javaModel) {
        Map<String, MethodModel> methods = new LinkedHashMap<>();
        for (ClassModel classModel : javaModel.classesByName.values()) {
            methods.putAll(classModel.methodsById);
        }
        return methods;
    }

    private List<CallEdge> buildCallEdges(JavaModel javaModel, Map<String, MethodModel> methodsById, InjectionRegistry injectionRegistry) {
        Map<String, CallEdgeAccumulator> edgeMap = new LinkedHashMap<>();

        for (ClassModel classModel : javaModel.classesByName.values()) {
            for (MethodModel methodModel : classModel.methodsById.values()) {
                for (CallSite call : methodModel.calls) {
                    Map<String, String> candidates = resolveCallCandidates(
                            classModel,
                            methodModel,
                            call,
                            javaModel,
                            methodsById,
                            injectionRegistry,
                            0
                    );
                    for (Map.Entry<String, String> candidate : candidates.entrySet()) {
                        String key = methodModel.id + "->" + candidate.getKey();
                        CallEdgeAccumulator accumulator = edgeMap.computeIfAbsent(
                                key,
                                k -> new CallEdgeAccumulator(methodModel.id, candidate.getKey(), call.line)
                        );
                        accumulator.reasons.add(candidate.getValue());
                    }
                }
            }
        }

        List<CallEdge> edges = edgeMap.values().stream()
                .map(a -> new CallEdge(a.from, a.to, String.join("|", a.reasons), a.line))
                .collect(Collectors.toCollection(ArrayList::new));
        edges.sort(Comparator.comparing((CallEdge e) -> e.from).thenComparing(e -> e.to));
        return edges;
    }

    private Map<String, String> resolveCallCandidates(ClassModel classModel,
                                                      MethodModel methodModel,
                                                      CallSite call,
                                                      JavaModel javaModel,
                                                      Map<String, MethodModel> methodsById,
                                                      InjectionRegistry injectionRegistry,
                                                      int depth) {
        if (depth > 6) {
            return Collections.emptyMap();
        }

        Map<String, String> resolved = new LinkedHashMap<>();
        if (call.scopeType == ScopeType.UNSCOPED || call.scopeType == ScopeType.THIS) {
            addResolvedTargets(resolved, resolveMethodInClassAndParents(classModel, call, javaModel), "JAVA:this");
            return resolved;
        }

        if (call.scopeType == ScopeType.NAME && call.scopeToken != null) {
            Map<String, TargetSet> classTargets = injectionRegistry.targetsByClass.getOrDefault(classModel.fqcn, Collections.emptyMap());
            TargetSet targetSet = classTargets.get(call.scopeToken);
            if (targetSet != null && !targetSet.targets.isEmpty()) {
                for (String targetClass : targetSet.targets) {
                    addResolvedTargets(
                            resolved,
                            resolveMethodByName(targetClass, call, javaModel),
                            targetSet.reasonSummary()
                    );
                }
            } else {
                String localType = methodModel.visibleVariableTypes.get(call.scopeToken);
                if (localType != null) {
                    Set<String> localCandidates = resolveClassesByType(localType, javaModel);
                    for (String localCandidate : localCandidates) {
                        addResolvedTargets(resolved, resolveMethodByName(localCandidate, call, javaModel), "JAVA:local-var");
                    }
                } else {
                    FieldModel fieldModel = classModel.fields.get(call.scopeToken);
                    if (fieldModel != null) {
                        Set<String> typeCandidates = resolveClassesByType(fieldModel.typeName, javaModel);
                        for (String typeCandidate : typeCandidates) {
                            addResolvedTargets(resolved, resolveMethodByName(typeCandidate, call, javaModel), "JAVA:field-type");
                        }
                    }
                }
            }
            return resolved;
        }

        if (call.scopeType == ScopeType.METHOD_CALL && call.scopeCall != null) {
            Map<String, String> scopeCalls = resolveCallCandidates(
                    classModel,
                    methodModel,
                    call.scopeCall,
                    javaModel,
                    methodsById,
                    injectionRegistry,
                    depth + 1
            );
            for (Map.Entry<String, String> scopeCall : scopeCalls.entrySet()) {
                MethodModel scopeMethod = methodsById.get(scopeCall.getKey());
                if (scopeMethod == null || "void".equals(normalizeTypeName(scopeMethod.returnTypeName))) {
                    continue;
                }
                for (String scopeType : resolveClassesByType(scopeMethod.returnTypeName, javaModel)) {
                    addResolvedTargets(
                            resolved,
                            resolveMethodByName(scopeType, call, javaModel),
                            "CHAIN:" + scopeCall.getValue()
                    );
                }
            }
        }
        return resolved;
    }

    private void addResolvedTargets(Map<String, String> resolved, Set<String> targets, String reason) {
        for (String target : targets) {
            resolved.merge(target, reason, (a, b) -> a.equals(b) ? a : a + "|" + b);
        }
    }

    private List<Endpoint> buildEndpoints(JavaModel javaModel, XmlModel xmlModel, BeanRegistry beanRegistry) {
        List<Endpoint> endpoints = new ArrayList<>();

        for (ClassModel classModel : javaModel.classesByName.values()) {
            if (!classModel.isController()) {
                continue;
            }
            for (MethodModel method : classModel.methodsById.values()) {
                boolean methodMapped = !Collections.disjoint(method.annotations, REQUEST_MAPPING_ANNOTATIONS);
                if (!methodMapped) {
                    continue;
                }
                List<String> methodPaths = method.requestPaths.isEmpty() ? listOf("") : method.requestPaths;
                List<String> classPaths = classModel.classRequestPaths.isEmpty() ? listOf("") : classModel.classRequestPaths;
                Set<String> httpMethods = new LinkedHashSet<>();
                httpMethods.addAll(classModel.classHttpMethods);
                httpMethods.addAll(method.httpMethods);
                if (httpMethods.isEmpty()) {
                    httpMethods.add("ANY");
                }

                for (String classPath : classPaths) {
                    for (String methodPath : methodPaths) {
                        String full = joinHttpPath(classPath, methodPath);
                        endpoints.add(new Endpoint(full, new ArrayList<>(httpMethods), "ANNOTATION", method.id));
                    }
                }
            }
        }

        for (XmlUrlMapping mapping : xmlModel.urlMappings) {
            String beanId = xmlModel.resolveAlias(mapping.beanId);
            Set<String> beanClasses = resolveClassesByBeanId(beanId, beanRegistry, javaModel);
            for (String beanClass : beanClasses) {
                ClassModel classModel = javaModel.classesByName.get(beanClass);
                if (classModel == null) {
                    continue;
                }
                Optional<MethodModel> entryMethod = classModel.methodsByName.getOrDefault("handleRequest", listOf()).stream().findFirst();
                if (!entryMethod.isPresent()) {
                    entryMethod = classModel.methodsByName.values().stream().flatMap(Collection::stream).findFirst();
                }
                entryMethod.ifPresent(methodModel -> endpoints.add(new Endpoint(mapping.path, listOf("ANY"), "XML", methodModel.id)));
            }
        }

        endpoints.sort(Comparator.comparing((Endpoint e) -> e.path).thenComparing(e -> e.entryMethodId));
        return endpoints;
    }

    private List<List<String>> enumeratePaths(String entryMethodId,
                                              Map<String, List<CallEdge>> edgesFrom,
                                              int maxDepth,
                                              int maxPathsPerEndpoint) {
        List<List<String>> result = new ArrayList<>();
        Deque<String> currentPath = new ArrayDeque<>();
        Set<String> visiting = new LinkedHashSet<>();
        dfs(entryMethodId, edgesFrom, maxDepth, maxPathsPerEndpoint, result, currentPath, visiting);
        return result;
    }

    private void dfs(String methodId,
                     Map<String, List<CallEdge>> edgesFrom,
                     int depthLeft,
                     int maxPathsPerEndpoint,
                     List<List<String>> result,
                     Deque<String> currentPath,
                     Set<String> visiting) {
        if (result.size() >= maxPathsPerEndpoint) {
            return;
        }

        currentPath.addLast(methodId);
        visiting.add(methodId);

        List<CallEdge> outgoing = edgesFrom.getOrDefault(methodId, listOf());
        boolean hasNext = false;

        if (depthLeft > 0) {
            for (CallEdge edge : outgoing) {
                if (visiting.contains(edge.to)) {
                    continue;
                }
                hasNext = true;
                dfs(edge.to, edgesFrom, depthLeft - 1, maxPathsPerEndpoint, result, currentPath, visiting);
                if (result.size() >= maxPathsPerEndpoint) {
                    break;
                }
            }
        }

        if (!hasNext) {
            result.add(new ArrayList<>(currentPath));
        }

        visiting.remove(methodId);
        currentPath.removeLast();
    }

    private Set<String> resolveMethodInClassAndParents(ClassModel classModel, CallSite call, JavaModel javaModel) {
        Set<String> resolved = new LinkedHashSet<>(resolveMethodByName(classModel.fqcn, call, javaModel));
        String superType = classModel.superType;
        Set<String> guard = new HashSet<>();

        while (superType != null && guard.add(superType)) {
            Set<String> superCandidates = resolveClassesByType(superType, javaModel);
            if (superCandidates.isEmpty()) {
                break;
            }
            boolean moved = false;
            for (String superCandidate : superCandidates) {
                resolved.addAll(resolveMethodByName(superCandidate, call, javaModel));
                ClassModel superClass = javaModel.classesByName.get(superCandidate);
                if (superClass != null && superClass.superType != null) {
                    superType = superClass.superType;
                    moved = true;
                    break;
                }
            }
            if (!moved) {
                break;
            }
        }
        return resolved;
    }

    private Set<String> resolveMethodByName(String className, CallSite call, JavaModel javaModel) {
        ClassModel target = javaModel.classesByName.get(className);
        if (target == null) {
            return setOf();
        }
        List<MethodModel> methods = target.methodsByName.getOrDefault(call.methodName, listOf());
        return methods.stream()
                .filter(m -> m.parameterCount == call.argumentCount)
                .map(m -> m.id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> resolveClassesByBeanId(String beanId, BeanRegistry beanRegistry, JavaModel javaModel) {
        if (beanId == null || isBlank(beanId)) {
            return setOf();
        }
        String resolvedBeanId = resolveAlias(beanId, beanRegistry.aliasToName);
        Set<String> classes = beanRegistry.beanIdToClasses.getOrDefault(resolvedBeanId, setOf());
        if (!classes.isEmpty()) {
            return classes;
        }

        Set<String> byType = resolveClassesByType(beanId, javaModel);
        if (!byType.isEmpty()) {
            return byType;
        }

        String decapitalized = decapitalize(beanId);
        return beanRegistry.beanIdToClasses.getOrDefault(decapitalized, setOf());
    }

    private Set<String> resolveClassesByType(String typeName, JavaModel javaModel) {
        String normalized = normalizeTypeName(typeName);
        if (isBlank(normalized)) {
            return setOf();
        }

        Set<String> roots = resolveDirectTypeNames(normalized, javaModel);
        if (roots.isEmpty() && normalized.contains(".")) {
            roots = setOf(normalized);
        }

        Set<String> candidates = new LinkedHashSet<>(roots);
        for (ClassModel classModel : javaModel.classesByName.values()) {
            for (String root : roots) {
                if (isAssignableTo(classModel.fqcn, root, javaModel, new HashSet<>())) {
                    candidates.add(classModel.fqcn);
                    break;
                }
            }
        }
        return candidates;
    }

    private boolean isAssignableTo(String candidateClass,
                                   String targetType,
                                   JavaModel javaModel,
                                   Set<String> visiting) {
        if (candidateClass.equals(targetType)) {
            return true;
        }
        if (!visiting.add(candidateClass)) {
            return false;
        }

        ClassModel candidate = javaModel.classesByName.get(candidateClass);
        if (candidate == null) {
            return simpleName(candidateClass).equals(simpleName(targetType));
        }

        if (simpleName(candidateClass).equals(simpleName(targetType))) {
            return true;
        }

        if (candidate.superType != null) {
            for (String superType : resolveDirectTypeNames(candidate.superType, javaModel)) {
                if (isAssignableTo(superType, targetType, javaModel, visiting)) {
                    return true;
                }
            }
        }

        for (String iface : candidate.implementedTypes) {
            for (String ifaceType : resolveDirectTypeNames(iface, javaModel)) {
                if (isAssignableTo(ifaceType, targetType, javaModel, visiting)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Set<String> resolveDirectTypeNames(String typeName, JavaModel javaModel) {
        String normalized = normalizeTypeName(typeName);
        if (isBlank(normalized)) {
            return setOf();
        }
        Set<String> direct = new LinkedHashSet<>();
        if (normalized.contains(".")) {
            direct.add(normalized);
            direct.addAll(javaModel.simpleToFqcn.getOrDefault(simpleName(normalized), setOf()));
        } else {
            direct.addAll(javaModel.simpleToFqcn.getOrDefault(normalized, setOf()));
        }
        return direct;
    }

    private static String resolveAlias(String beanId, Map<String, String> aliasToName) {
        String current = beanId;
        Set<String> seen = new HashSet<>();
        while (aliasToName.containsKey(current) && seen.add(current)) {
            current = aliasToName.get(current);
        }
        return current;
    }

    private static String methodId(String className, String methodName, int parameterCount) {
        return className + "#" + methodName + "/" + parameterCount;
    }

    private static String normalizeTypeName(String typeName) {
        if (typeName == null) {
            return "";
        }
        String normalized = typeName;
        int genericIndex = normalized.indexOf('<');
        if (genericIndex >= 0) {
            normalized = normalized.substring(0, genericIndex);
        }
        normalized = normalized.replace("[]", "").trim();
        if (normalized.contains(".")) {
            return normalized;
        }
        if (normalized.contains(" ")) {
            String[] parts = normalized.split("\\s+");
            normalized = parts[parts.length - 1];
        }
        return normalized;
    }

    private static String simpleName(String fqcnOrSimple) {
        if (fqcnOrSimple == null) {
            return "";
        }
        int idx = fqcnOrSimple.lastIndexOf('.');
        return idx >= 0 ? fqcnOrSimple.substring(idx + 1) : fqcnOrSimple;
    }

    private static Set<String> annotationNames(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .map(SpringCallPathAnalyzer::simpleName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static boolean hasAnyAnnotation(NodeWithAnnotations<?> node, Set<String> names) {
        Set<String> anns = annotationNames(node);
        for (String name : names) {
            if (anns.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static Optional<String> extractBeanNameFromStereotype(NodeWithAnnotations<?> node) {
        for (AnnotationExpr annotation : node.getAnnotations()) {
            String annName = simpleName(annotation.getNameAsString());
            if (!COMPONENT_ANNOTATIONS.contains(annName)) {
                continue;
            }
            Optional<String> value = extractAnnotationValue(annotation, setOf("value", "name"));
            if (value.isPresent() && !isBlank(value.get())) {
                return value;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> extractResourceName(List<AnnotationExpr> annotations) {
        for (AnnotationExpr annotation : annotations) {
            if (!"Resource".equals(simpleName(annotation.getNameAsString()))) {
                continue;
            }
            Optional<String> value = extractAnnotationValue(annotation, setOf("name", "value"));
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    private static List<String> extractPathsFromAnnotations(List<AnnotationExpr> annotations) {
        List<String> paths = new ArrayList<>();
        for (AnnotationExpr annotation : annotations) {
            String annName = simpleName(annotation.getNameAsString());
            if (!REQUEST_MAPPING_ANNOTATIONS.contains(annName)) {
                continue;
            }
            Optional<String> path = extractAnnotationValue(annotation, setOf("value", "path"));
            path.ifPresent(paths::add);
            paths.addAll(extractAnnotationArrayValues(annotation, setOf("value", "path")));
        }
        return paths.stream().filter(s -> !isBlank(s)).distinct().collect(Collectors.toList());
    }

    private static Set<String> extractHttpMethods(List<AnnotationExpr> annotations) {
        Set<String> methods = new LinkedHashSet<>();
        for (AnnotationExpr annotation : annotations) {
            String annName = simpleName(annotation.getNameAsString());
            switch (annName) {
                case "GetMapping":
                    methods.add("GET");
                    break;
                case "PostMapping":
                    methods.add("POST");
                    break;
                case "PutMapping":
                    methods.add("PUT");
                    break;
                case "DeleteMapping":
                    methods.add("DELETE");
                    break;
                case "PatchMapping":
                    methods.add("PATCH");
                    break;
                case "RequestMapping":
                    methods.addAll(extractRequestMappingMethodValues(annotation));
                    break;
                default:
                    break;
            }
        }
        return methods;
    }

    private static Set<String> extractRequestMappingMethodValues(AnnotationExpr annotation) {
        Set<String> methods = new LinkedHashSet<>();
        if (annotation instanceof NormalAnnotationExpr) {
            NormalAnnotationExpr normal = (NormalAnnotationExpr) annotation;
            for (MemberValuePair pair : normal.getPairs()) {
                if (!"method".equals(pair.getNameAsString())) {
                    continue;
                }
                Expression value = pair.getValue();
                if (value instanceof ArrayInitializerExpr) {
                    ArrayInitializerExpr arrayInitializerExpr = (ArrayInitializerExpr) value;
                    for (Expression e : arrayInitializerExpr.getValues()) {
                        methods.add(parseRequestMethodConstant(e));
                    }
                } else {
                    methods.add(parseRequestMethodConstant(value));
                }
            }
        }
        methods.removeIf(SpringCallPathAnalyzer::isBlank);
        return methods;
    }

    private static String parseRequestMethodConstant(Expression expr) {
        String raw = expr.toString();
        int idx = raw.lastIndexOf('.');
        return idx >= 0 ? raw.substring(idx + 1) : raw;
    }

    private static Optional<String> extractAnnotationValue(AnnotationExpr annotation, Set<String> keys) {
        if (annotation instanceof SingleMemberAnnotationExpr) {
            SingleMemberAnnotationExpr singleMember = (SingleMemberAnnotationExpr) annotation;
            if (!keys.contains("value")) {
                return Optional.empty();
            }
            Expression value = singleMember.getMemberValue();
            if (value instanceof ArrayInitializerExpr) {
                return Optional.empty();
            }
            if (value instanceof StringLiteralExpr) {
                StringLiteralExpr sle = (StringLiteralExpr) value;
                return Optional.ofNullable(sle.asString());
            }
            return Optional.of(value.toString().replace("\"", ""));
        }
        if (annotation instanceof NormalAnnotationExpr) {
            NormalAnnotationExpr normalAnnotationExpr = (NormalAnnotationExpr) annotation;
            for (MemberValuePair pair : normalAnnotationExpr.getPairs()) {
                if (!keys.contains(pair.getNameAsString())) {
                    continue;
                }
                Expression value = pair.getValue();
                if (value instanceof ArrayInitializerExpr) {
                    return Optional.empty();
                }
                if (value instanceof StringLiteralExpr) {
                    StringLiteralExpr sle = (StringLiteralExpr) value;
                    return Optional.ofNullable(sle.asString());
                }
                return Optional.of(value.toString().replace("\"", ""));
            }
        }
        return Optional.empty();
    }

    private static List<String> extractAnnotationArrayValues(AnnotationExpr annotation, Set<String> keys) {
        if (!(annotation instanceof NormalAnnotationExpr)) {
            return listOf();
        }
        NormalAnnotationExpr normal = (NormalAnnotationExpr) annotation;
        List<String> values = new ArrayList<>();
        for (MemberValuePair pair : normal.getPairs()) {
            if (!keys.contains(pair.getNameAsString())) {
                continue;
            }
            if (!(pair.getValue() instanceof ArrayInitializerExpr)) {
                continue;
            }
            ArrayInitializerExpr array = (ArrayInitializerExpr) pair.getValue();
            for (Expression value : array.getValues()) {
                if (value instanceof StringLiteralExpr) {
                    StringLiteralExpr sle = (StringLiteralExpr) value;
                    values.add(sle.asString());
                } else {
                    values.add(value.toString().replace("\"", ""));
                }
            }
        }
        return values;
    }

    private static String joinHttpPath(String left, String right) {
        String l = left == null ? "" : left.trim();
        String r = right == null ? "" : right.trim();
        if (l.isEmpty() && r.isEmpty()) {
            return "/";
        }
        if (l.isEmpty()) {
            return normalizeHttpPath(r);
        }
        if (r.isEmpty()) {
            return normalizeHttpPath(l);
        }
        return normalizeHttpPath(l + "/" + r);
    }

    private static String normalizeHttpPath(String path) {
        String normalized = path.replaceAll("/{2,}", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String decapitalize(String name) {
        if (name == null || isBlank(name)) {
            return name;
        }
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1)) && Character.isUpperCase(name.charAt(0))) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @SafeVarargs
    private static <T> Set<T> setOf(T... values) {
        Set<T> set = new LinkedHashSet<T>();
        if (values != null) {
            set.addAll(Arrays.asList(values));
        }
        return set;
    }

    @SafeVarargs
    private static <T> List<T> listOf(T... values) {
        if (values == null || values.length == 0) {
            return new ArrayList<T>();
        }
        return new ArrayList<T>(Arrays.asList(values));
    }

    public enum ScopeType {
        UNSCOPED,
        THIS,
        NAME,
        METHOD_CALL,
        OTHER
    }

    public static class CliOptions {
        public final Path projectRoot;
        public final Path outputDir;
        public final int maxDepth;
        public final int maxPathsPerEndpoint;

        private CliOptions(Path projectRoot, Path outputDir, int maxDepth, int maxPathsPerEndpoint) {
            this.projectRoot = projectRoot;
            this.outputDir = outputDir;
            this.maxDepth = maxDepth;
            this.maxPathsPerEndpoint = maxPathsPerEndpoint;
        }

        public static CliOptions parse(String[] args) {
            Path projectRoot = Paths.get(".").toAbsolutePath().normalize();
            Path outputDir = projectRoot.resolve("build/reports/spring-call-path");
            int maxDepth = 8;
            int maxPaths = 200;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--project".equals(arg) && i + 1 < args.length) {
                    projectRoot = Paths.get(args[++i]).toAbsolutePath().normalize();
                } else if ("--out".equals(arg) && i + 1 < args.length) {
                    outputDir = Paths.get(args[++i]).toAbsolutePath().normalize();
                } else if ("--max-depth".equals(arg) && i + 1 < args.length) {
                    maxDepth = Integer.parseInt(args[++i]);
                } else if ("--max-paths".equals(arg) && i + 1 < args.length) {
                    maxPaths = Integer.parseInt(args[++i]);
                }
            }

            return new CliOptions(projectRoot, outputDir, maxDepth, maxPaths);
        }
    }

    public static class AnalysisReport {
        public final String generatedAt;
        public final String projectRoot;
        public final Map<String, String> methodDisplay;
        public final List<CallEdge> edges;
        public final List<EndpointPaths> endpoints;

        public AnalysisReport(String generatedAt,
                              String projectRoot,
                              Map<String, String> methodDisplay,
                              List<CallEdge> edges,
                              List<EndpointPaths> endpoints) {
            this.generatedAt = generatedAt;
            this.projectRoot = projectRoot;
            this.methodDisplay = methodDisplay;
            this.edges = edges;
            this.endpoints = endpoints;
        }

        public String toDot() {
            StringBuilder sb = new StringBuilder();
            sb.append("digraph CallGraph {\n");
            sb.append("  rankdir=LR;\n");
            sb.append("  node [shape=box, fontsize=10];\n");

            for (Map.Entry<String, String> entry : methodDisplay.entrySet()) {
                sb.append("  \"").append(escape(entry.getKey())).append("\"")
                        .append(" [label=\"").append(escape(entry.getValue())).append("\"];\n");
            }

            int endpointIdx = 0;
            for (EndpointPaths endpoint : endpoints) {
                String endpointNode = "endpoint_" + endpointIdx++;
                sb.append("  \"").append(endpointNode).append("\" [shape=oval, label=\"")
                        .append(escape(endpoint.path + " [" + String.join(",", endpoint.httpMethods) + "]"))
                        .append("\"];\n");
                sb.append("  \"").append(endpointNode).append("\" -> \"")
                        .append(escape(endpoint.entryMethodId)).append("\" [style=dashed];\n");
            }

            for (CallEdge edge : edges) {
                sb.append("  \"").append(escape(edge.from)).append("\" -> \"")
                        .append(escape(edge.to)).append("\"")
                        .append(" [label=\"").append(escape(edge.reason)).append("\"];\n");
            }
            sb.append("}\n");
            return sb.toString();
        }

        private static String escape(String raw) {
            return raw.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    public static class EndpointPaths {
        public final String path;
        public final List<String> httpMethods;
        public final String source;
        public final String entryMethodId;
        public final List<List<String>> paths;

        public EndpointPaths(String path,
                             List<String> httpMethods,
                             String source,
                             String entryMethodId,
                             List<List<String>> paths) {
            this.path = path;
            this.httpMethods = httpMethods;
            this.source = source;
            this.entryMethodId = entryMethodId;
            this.paths = paths;
        }
    }

    public static class CallEdge {
        public final String from;
        public final String to;
        public final String reason;
        public final int line;

        public CallEdge(String from, String to, String reason, int line) {
            this.from = from;
            this.to = to;
            this.reason = reason;
            this.line = line;
        }
    }

    public static class Endpoint {
        public final String path;
        public final List<String> httpMethods;
        public final String source;
        public final String entryMethodId;

        public Endpoint(String path, List<String> httpMethods, String source, String entryMethodId) {
            this.path = path;
            this.httpMethods = httpMethods;
            this.source = source;
            this.entryMethodId = entryMethodId;
        }
    }

    public static class JavaModel {
        public final Map<String, ClassModel> classesByName = new LinkedHashMap<>();
        public final Map<String, Set<String>> simpleToFqcn = new LinkedHashMap<>();
    }

    public static class XmlModel {
        public final Map<String, String> beansById = new LinkedHashMap<>();
        public final Map<String, String> aliasToName = new LinkedHashMap<>();
        public final List<XmlPropertyRef> propertyRefs = new ArrayList<>();
        public final List<XmlUrlMapping> urlMappings = new ArrayList<>();

        public String resolveAlias(String beanId) {
            return SpringCallPathAnalyzer.resolveAlias(beanId, aliasToName);
        }
    }

    public static class BeanRegistry {
        public final Map<String, Set<String>> beanIdToClasses = new LinkedHashMap<>();
        public final Map<String, String> aliasToName = new LinkedHashMap<>();
    }

    public static class InjectionRegistry {
        public final Map<String, Map<String, TargetSet>> targetsByClass = new LinkedHashMap<>();
    }

    public static class TargetSet {
        public final Set<String> targets = new LinkedHashSet<>();
        public final Set<String> reasons = new LinkedHashSet<>();

        public void addAll(Set<String> entries, String reason) {
            targets.addAll(entries);
            reasons.add(reason);
        }

        public String reasonSummary() {
            return String.join("|", reasons);
        }
    }

    public static class ClassModel {
        public final String fqcn;
        public final String simpleName;
        public final String packageName;
        public final ImportContext importContext;
        public final Set<String> annotations = new LinkedHashSet<>();
        public final Map<String, FieldModel> fields = new LinkedHashMap<>();
        public final Map<String, MethodModel> methodsById = new LinkedHashMap<>();
        public final Map<String, List<MethodModel>> methodsByName = new LinkedHashMap<>();
        public final List<String> implementedTypes = new ArrayList<>();
        public final Map<String, String> constructorInjectedFieldTypes = new LinkedHashMap<>();
        public final List<String> classRequestPaths = new ArrayList<>();
        public final Set<String> classHttpMethods = new LinkedHashSet<>();
        public String superType;
        public String beanNameFromAnnotation;

        public ClassModel(String fqcn, String simpleName, String packageName, ImportContext importContext) {
            this.fqcn = fqcn;
            this.simpleName = simpleName;
            this.packageName = packageName;
            this.importContext = importContext;
        }

        public boolean isComponent() {
            return !Collections.disjoint(annotations, COMPONENT_ANNOTATIONS);
        }

        public boolean isController() {
            return annotations.contains("Controller") || annotations.contains("RestController");
        }
    }

    public static class FieldModel {
        public final String name;
        public final String typeName;
        public final Set<String> annotations;
        public final String resourceName;

        public FieldModel(String name, String typeName, Set<String> annotations, String resourceName) {
            this.name = name;
            this.typeName = typeName;
            this.annotations = annotations;
            this.resourceName = resourceName;
        }
    }

    public static class MethodModel {
        public final String id;
        public final String className;
        public final String name;
        public final int parameterCount;
        public final int line;
        public final String returnTypeName;
        public final Set<String> annotations = new LinkedHashSet<>();
        public final List<String> requestPaths = new ArrayList<>();
        public final Set<String> httpMethods = new LinkedHashSet<>();
        public final List<CallSite> calls = new ArrayList<>();
        public final Map<String, String> visibleVariableTypes = new LinkedHashMap<>();

        public MethodModel(String id, String className, String name, int parameterCount, int line, String returnTypeName) {
            this.id = id;
            this.className = className;
            this.name = name;
            this.parameterCount = parameterCount;
            this.line = line;
            this.returnTypeName = returnTypeName;
        }

        public String display() {
            return className + "#" + name + "(" + parameterCount + ")";
        }
    }

    public static class CallSite {
        public final ScopeType scopeType;
        public final String scopeToken;
        public final CallSite scopeCall;
        public final String methodName;
        public final int argumentCount;
        public final int line;

        public CallSite(ScopeType scopeType, String scopeToken, CallSite scopeCall, String methodName, int argumentCount, int line) {
            this.scopeType = scopeType;
            this.scopeToken = scopeToken;
            this.scopeCall = scopeCall;
            this.methodName = methodName;
            this.argumentCount = argumentCount;
            this.line = line;
        }
    }

    public static class CallEdgeAccumulator {
        public final String from;
        public final String to;
        public final Set<String> reasons = new LinkedHashSet<>();
        public final int line;

        public CallEdgeAccumulator(String from, String to, int line) {
            this.from = from;
            this.to = to;
            this.line = line;
        }
    }

    public static class XmlPropertyRef {
        public final String fromBeanId;
        public final String propertyName;
        public final String toBeanId;

        public XmlPropertyRef(String fromBeanId, String propertyName, String toBeanId) {
            this.fromBeanId = fromBeanId;
            this.propertyName = propertyName;
            this.toBeanId = toBeanId;
        }
    }

    public static class XmlUrlMapping {
        public final String path;
        public final String beanId;

        public XmlUrlMapping(String path, String beanId) {
            this.path = path;
            this.beanId = beanId;
        }
    }

    public static class ImportContext {
        public final String packageName;
        public final Map<String, String> directImports;
        public final List<String> wildcardImports;

        public ImportContext(String packageName, Map<String, String> directImports, List<String> wildcardImports) {
            this.packageName = packageName;
            this.directImports = directImports;
            this.wildcardImports = wildcardImports;
        }

        public static ImportContext from(CompilationUnit cu, String packageName) {
            Map<String, String> direct = new LinkedHashMap<>();
            List<String> wildcard = new ArrayList<>();
            for (ImportDeclaration importDeclaration : cu.getImports()) {
                String value = importDeclaration.getNameAsString();
                if (importDeclaration.isAsterisk()) {
                    wildcard.add(value);
                } else {
                    direct.put(simpleName(value), value);
                }
            }
            return new ImportContext(packageName, direct, wildcard);
        }
    }
}
