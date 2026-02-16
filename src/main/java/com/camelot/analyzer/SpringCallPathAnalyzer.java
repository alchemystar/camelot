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
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.ReturnStmt;
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
    private DebugLogger debugLogger = DebugLogger.disabled();
    private static final Set<String> COMPONENT_ANNOTATIONS = setOf(
            "Component", "Service", "Repository", "Controller", "RestController", "Configuration"
    );
    private static final Set<String> INJECTION_ANNOTATIONS = setOf("Autowired", "Inject", "Resource");
    private static final Set<String> REQUEST_MAPPING_ANNOTATIONS = setOf(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping"
    );
    private static final Set<String> PIPELINE_ASSEMBLY_METHOD_NAMES = setOf(
            "add", "addlast", "addfirst", "append", "then", "link", "register",
            "stage", "withstage", "pipe", "next", "step", "addstep", "addhandler"
    );
    private static final Set<String> PIPELINE_TERMINAL_METHOD_NAMES = setOf(
            "execute", "run", "start", "process", "handle", "invoke", "fire"
    );
    private static final Set<String> PIPELINE_SKIP_STEP_METHOD_NAMES = setOf(
            "iterator", "hasnext", "next", "size", "add", "get", "set"
    );
    private static final Set<String> JAVA_LANG_COMMON_TYPES = setOf(
            "Object", "String", "Boolean", "Byte", "Short", "Integer", "Long",
            "Float", "Double", "Character", "Number", "Void", "Class",
            "RuntimeException", "Exception", "Throwable", "Enum"
    );
    private static final int CALL_RESOLVE_MAX_DEPTH = 16;
    private static final int FLOW_TRACE_MAX_DEPTH = 50;

    public static void main(String[] args) throws Exception {
        CliOptions options = CliOptions.parse(args);
        Files.createDirectories(options.outputDir);
        Path debugPath = options.debugOut;
        if (debugPath == null) {
            debugPath = options.outputDir.resolve("analysis-debug.log");
        }
        DebugLogger debugLogger = new DebugLogger(options.debug, debugPath);
        SpringCallPathAnalyzer analyzer = new SpringCallPathAnalyzer();
        AnalysisReport report = analyzer.analyze(
                options.projectRoot,
                options.maxDepth,
                options.maxPathsPerEndpoint,
                options.endpointPath,
                options.entryMethod,
                debugLogger,
                options.externalRpcPrefixes,
                options.nonExternalRpcPrefixes
        );
        Path jsonPath = options.outputDir.resolve("analysis-report.json");
        Path dotPath = options.outputDir.resolve("call-graph.dot");
        Path dependencyTreePath = options.outputDir.resolve("external-dependency-tree.txt");

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(jsonPath.toFile(), report);
        Files.write(dotPath, report.toDot().getBytes(StandardCharsets.UTF_8));
        Files.write(dependencyTreePath, report.toDependencyTreeText().getBytes(StandardCharsets.UTF_8));

        System.out.println("Analysis finished.");
        System.out.println("JSON report: " + jsonPath.toAbsolutePath());
        System.out.println("DOT graph:   " + dotPath.toAbsolutePath());
        System.out.println("Dep tree:    " + dependencyTreePath.toAbsolutePath());
        System.out.println("Endpoints:   " + report.endpoints.size());
        System.out.println("Methods:     " + report.methodDisplay.size());
        System.out.println("Edges:       " + report.edges.size());
        System.out.println("Ext trees:   " + report.externalDependencyTrees.size());
        if (!options.externalRpcPrefixes.isEmpty()) {
            System.out.println("External RPC prefixes: " + options.externalRpcPrefixes);
        }
        if (!options.nonExternalRpcPrefixes.isEmpty()) {
            System.out.println("Non-external RPC prefixes: " + options.nonExternalRpcPrefixes);
        }
        if (!isBlank(options.endpointPath)) {
            System.out.println("Endpoint filter: " + options.endpointPath);
            if (report.endpoints.isEmpty()) {
                System.out.println("No endpoint matched the filter.");
            }
        }
        if (!isBlank(options.entryMethod)) {
            System.out.println("Entry method filter: " + options.entryMethod);
            if (report.endpoints.isEmpty()) {
                System.out.println("No method entry matched the filter.");
            }
        }
        if (debugLogger.isEnabled()) {
            debugLogger.flush();
            System.out.println("Debug log:   " + debugLogger.getOutPath().toAbsolutePath());
        }
    }

    public AnalysisReport analyze(Path projectRoot, int maxDepth, int maxPathsPerEndpoint) throws IOException {
        return analyze(
                projectRoot,
                maxDepth,
                maxPathsPerEndpoint,
                null,
                null,
                DebugLogger.disabled(),
                Collections.<String>emptySet(),
                Collections.<String>emptySet()
        );
    }

    public AnalysisReport analyze(Path projectRoot,
                                  int maxDepth,
                                  int maxPathsPerEndpoint,
                                  String endpointPathFilter) throws IOException {
        return analyze(
                projectRoot,
                maxDepth,
                maxPathsPerEndpoint,
                endpointPathFilter,
                null,
                DebugLogger.disabled(),
                Collections.<String>emptySet(),
                Collections.<String>emptySet()
        );
    }

    public AnalysisReport analyze(Path projectRoot,
                                  int maxDepth,
                                  int maxPathsPerEndpoint,
                                  String endpointPathFilter,
                                  String entryMethodFilter) throws IOException {
        return analyze(
                projectRoot,
                maxDepth,
                maxPathsPerEndpoint,
                endpointPathFilter,
                entryMethodFilter,
                DebugLogger.disabled(),
                Collections.<String>emptySet(),
                Collections.<String>emptySet()
        );
    }

    public AnalysisReport analyze(Path projectRoot,
                                  int maxDepth,
                                  int maxPathsPerEndpoint,
                                  String endpointPathFilter,
                                  String entryMethodFilter,
                                  DebugLogger debugLogger) throws IOException {
        return analyze(
                projectRoot,
                maxDepth,
                maxPathsPerEndpoint,
                endpointPathFilter,
                entryMethodFilter,
                debugLogger,
                Collections.<String>emptySet(),
                Collections.<String>emptySet()
        );
    }

    public AnalysisReport analyze(Path projectRoot,
                                  int maxDepth,
                                  int maxPathsPerEndpoint,
                                  String endpointPathFilter,
                                  String entryMethodFilter,
                                  DebugLogger debugLogger,
                                  Set<String> externalRpcPrefixes,
                                  Set<String> nonExternalRpcPrefixes) throws IOException {
        DependencyAnalysisModule dependencyAnalysisModule = new DependencyAnalysisModule(this);
        return dependencyAnalysisModule.analyzeWithDependencies(
                projectRoot,
                maxDepth,
                maxPathsPerEndpoint,
                endpointPathFilter,
                entryMethodFilter,
                debugLogger,
                externalRpcPrefixes,
                nonExternalRpcPrefixes
        );
    }

    public CodeAnalysisResult analyzeCode(Path projectRoot,
                                          int maxDepth,
                                          int maxPathsPerEndpoint,
                                          String endpointPathFilter,
                                          String entryMethodFilter,
                                          DebugLogger debugLogger) throws IOException {
        DebugLogger previousLogger = this.debugLogger;
        this.debugLogger = debugLogger == null ? DebugLogger.disabled() : debugLogger;
        try {
        JavaModel javaModel = parseJava(projectRoot);
        XmlModel xmlModel = parseXml(projectRoot);
        this.debugLogger.log(
                "START project=%s endpointFilter=%s entryMethodFilter=%s maxDepth=%d maxPaths=%d",
                projectRoot.toAbsolutePath(),
                endpointPathFilter,
                entryMethodFilter,
                maxDepth,
                maxPathsPerEndpoint
        );

        BeanRegistry beanRegistry = buildBeanRegistry(javaModel, xmlModel);
        InjectionRegistry injectionRegistry = buildInjectionRegistry(javaModel, xmlModel, beanRegistry);
        Map<String, MethodModel> methodsById = collectMethods(javaModel);
        List<Endpoint> endpoints = buildEndpoints(javaModel, xmlModel, beanRegistry);
        List<Endpoint> selectedEndpoints = new ArrayList<Endpoint>();
        if (!isBlank(endpointPathFilter)) {
            selectedEndpoints.addAll(selectEndpoints(endpoints, endpointPathFilter));
        } else if (isBlank(entryMethodFilter)) {
            selectedEndpoints.addAll(endpoints);
        }
        debugLogger.log("ENDPOINTS total=%d selected=%d", endpoints.size(), selectedEndpoints.size());
        Set<String> selectedEntryMethods = selectEntryMethods(entryMethodFilter, methodsById, javaModel);
        Set<String> traversalEntries = new LinkedHashSet<String>();
        for (Endpoint endpoint : selectedEndpoints) {
            traversalEntries.add(endpoint.entryMethodId);
        }
        traversalEntries.addAll(selectedEntryMethods);

        List<CallEdge> edges = buildCallEdgesFromEntries(
                javaModel,
                methodsById,
                injectionRegistry,
                traversalEntries,
                maxDepth
        );
        Map<String, List<CallEdge>> edgesFrom = edges.stream()
                .collect(Collectors.groupingBy(e -> e.from, LinkedHashMap::new, Collectors.toList()));

        List<EndpointPaths> endpointPaths = new ArrayList<>();
        for (Endpoint endpoint : selectedEndpoints) {
            List<List<String>> paths = enumeratePaths(endpoint.entryMethodId, edgesFrom, maxDepth, maxPathsPerEndpoint);
            endpointPaths.add(new EndpointPaths(endpoint.path, endpoint.httpMethods, endpoint.source, endpoint.entryMethodId, paths));
        }
        for (String entryMethodId : selectedEntryMethods) {
            List<List<String>> paths = enumeratePaths(entryMethodId, edgesFrom, maxDepth, maxPathsPerEndpoint);
            endpointPaths.add(new EndpointPaths(
                    "method:" + entryMethodId,
                    listOf("METHOD"),
                    "METHOD",
                    entryMethodId,
                    paths
            ));
        }

        Set<String> reachableMethodIds = collectReachableMethodIds(endpointPaths);
        debugLogger.log("REACHABLE methods=%d endpointPaths=%d", reachableMethodIds.size(), endpointPaths.size());
        List<CallEdge> filteredEdges = edges;
        Map<String, String> methodDisplay;
        if (reachableMethodIds.isEmpty() && isBlank(endpointPathFilter) && isBlank(entryMethodFilter)) {
            methodDisplay = methodsById.values().stream()
                    .collect(Collectors.toMap(m -> m.id, MethodModel::display, (a, b) -> a, LinkedHashMap::new));
        } else {
            filteredEdges = edges.stream()
                    .filter(e -> reachableMethodIds.contains(e.from) && reachableMethodIds.contains(e.to))
                    .collect(Collectors.toList());
            methodDisplay = new LinkedHashMap<String, String>();
            for (String methodId : reachableMethodIds) {
                MethodModel methodModel = methodsById.get(methodId);
                if (methodModel != null) {
                    methodDisplay.put(methodId, methodModel.display());
                }
            }
        }
        for (CallEdge edge : filteredEdges) {
            if (!methodDisplay.containsKey(edge.from)) {
                methodDisplay.put(edge.from, toMethodDisplay(edge.from, methodsById));
            }
            if (!methodDisplay.containsKey(edge.to)) {
                methodDisplay.put(edge.to, toMethodDisplay(edge.to, methodsById));
            }
        }

        return new CodeAnalysisResult(
                projectRoot.toAbsolutePath().toString(),
                javaModel,
                methodsById,
                methodDisplay,
                filteredEdges,
                endpointPaths
        );
        } finally {
            this.debugLogger = previousLogger;
        }
    }

    private List<Endpoint> selectEndpoints(List<Endpoint> endpoints, String endpointPathFilter) {
        if (isBlank(endpointPathFilter)) {
            return endpoints;
        }
        String normalizedFilter = normalizeHttpPath(endpointPathFilter.trim());
        List<Endpoint> selected = new ArrayList<Endpoint>();
        for (Endpoint endpoint : endpoints) {
            if (normalizedFilter.equals(normalizeHttpPath(endpoint.path))) {
                selected.add(endpoint);
            }
        }
        return selected;
    }

    private Set<String> selectEntryMethods(String entryMethodFilter,
                                           Map<String, MethodModel> methodsById,
                                           JavaModel javaModel) {
        if (isBlank(entryMethodFilter)) {
            return new LinkedHashSet<String>();
        }

        MethodSelector selector = MethodSelector.parse(entryMethodFilter.trim());
        debugLogger.log("ENTRY_METHOD_SELECTOR raw=%s class=%s method=%s arity=%s",
                entryMethodFilter,
                selector.className,
                selector.methodName,
                String.valueOf(selector.parameterCount));
        Set<String> selected = new LinkedHashSet<String>();
        for (MethodModel method : methodsById.values()) {
            if (selector.matches(method)) {
                selected.add(method.id);
            }
        }
        debugLogger.log("ENTRY_METHOD_SELECTED count=%d methods=%s", selected.size(), selected);

        Set<String> expanded = new LinkedHashSet<String>(selected);
        for (String methodId : selected) {
            MethodModel rootMethod = methodsById.get(methodId);
            if (rootMethod == null) {
                continue;
            }
            for (ClassModel classModel : javaModel.classesByName.values()) {
                if (!isAssignableTo(classModel.fqcn, rootMethod.className, javaModel, new HashSet<String>())) {
                    continue;
                }
                List<MethodModel> implementations = classModel.methodsByName.get(rootMethod.name);
                if (implementations == null) {
                    continue;
                }
                for (MethodModel implementation : implementations) {
                    if (implementation.parameterCount == rootMethod.parameterCount) {
                        expanded.add(implementation.id);
                    }
                }
            }
        }
        debugLogger.log("ENTRY_METHOD_EXPANDED count=%d methods=%s", expanded.size(), expanded);
        return expanded;
    }

    private Set<String> collectReachableMethodIds(List<EndpointPaths> endpointPaths) {
        Set<String> reachable = new LinkedHashSet<String>();
        for (EndpointPaths endpointPath : endpointPaths) {
            if (endpointPath.paths.isEmpty()) {
                reachable.add(endpointPath.entryMethodId);
            }
            for (List<String> path : endpointPath.paths) {
                reachable.addAll(path);
            }
        }
        return reachable;
    }

    private String toMethodDisplay(String methodId, Map<String, MethodModel> methodsById) {
        MethodModel model = methodsById.get(methodId);
        if (model != null) {
            return model.display();
        }
        MethodIdInfo info = MethodIdInfo.parse(methodId);
        if (info == null) {
            return methodId;
        }
        return info.className + "#" + info.methodName + "(" + info.parameterCount + ") [external]";
    }

    private JavaModel parseJava(Path projectRoot) throws IOException {
        JavaModel model = new JavaModel();
        JavaParser parser = new JavaParser();

        List<Path> javaFiles;
        try (Stream<Path> stream = Files.walk(projectRoot)) {
            javaFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(this::shouldAnalyzeJava)
                    .sorted()
                    .collect(Collectors.toList());
        }
        debugLogger.log("JAVA_SCAN files=%d", javaFiles.size());

        for (Path javaFile : javaFiles) {
            ParseResult<CompilationUnit> result = parser.parse(javaFile);
            if (!result.getResult().isPresent()) {
                debugLogger.log("JAVA_PARSE_SKIP file=%s reason=parse-failed", javaFile.toAbsolutePath());
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

                ClassModel classModel = new ClassModel(
                        fqcn,
                        simpleName,
                        packageName,
                        importContext,
                        declaration.isInterface()
                );
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
                        methodModel.parameterNames.add(parameter.getNameAsString());
                        methodModel.visibleVariableTypes.put(parameter.getNameAsString(), parameter.getType().asString());
                    }
                    for (VariableDeclarator variable : methodDeclaration.findAll(VariableDeclarator.class)) {
                        methodModel.visibleVariableTypes.put(variable.getNameAsString(), variable.getType().asString());
                    }

                    for (MethodCallExpr callExpr : methodDeclaration.findAll(MethodCallExpr.class)) {
                        CallSite callSite = toCallSite(callExpr);
                        methodModel.calls.add(callSite);
                        debugLogger.log(
                                "PARSE_CALL from=%s line=%d call=%s scope=%s scopeToken=%s",
                                methodModel.id,
                                callSite.line,
                                callExpr.toString(),
                                callSite.scopeType,
                                callSite.scopeToken
                        );
                    }

                    for (VariableDeclarator variable : methodDeclaration.findAll(VariableDeclarator.class)) {
                        if (!variable.getInitializer().isPresent()) {
                            continue;
                        }
                        ValueExpr valueExpr = toValueExpr(variable.getInitializer().get());
                        if (valueExpr == null) {
                            continue;
                        }
                        int line = variable.getBegin().map(p -> p.line).orElse(-1);
                        methodModel.assignments.add(new AssignmentFlow(variable.getNameAsString(), valueExpr, line));
                    }

                    for (AssignExpr assignExpr : methodDeclaration.findAll(AssignExpr.class)) {
                        String targetName = null;
                        if (assignExpr.getTarget() instanceof NameExpr) {
                            targetName = ((NameExpr) assignExpr.getTarget()).getNameAsString();
                        } else if (assignExpr.getTarget() instanceof FieldAccessExpr
                                && ((FieldAccessExpr) assignExpr.getTarget()).getScope() instanceof ThisExpr) {
                            targetName = ((FieldAccessExpr) assignExpr.getTarget()).getNameAsString();
                        }
                        if (isBlank(targetName)) {
                            continue;
                        }
                        ValueExpr valueExpr = toValueExpr(assignExpr.getValue());
                        if (valueExpr == null) {
                            continue;
                        }
                        int line = assignExpr.getBegin().map(p -> p.line).orElse(-1);
                        methodModel.assignments.add(new AssignmentFlow(targetName, valueExpr, line));
                    }

                    for (ReturnStmt returnStmt : methodDeclaration.findAll(ReturnStmt.class)) {
                        if (!returnStmt.getExpression().isPresent()) {
                            continue;
                        }
                        ValueExpr valueExpr = toValueExpr(returnStmt.getExpression().get());
                        if (valueExpr == null) {
                            continue;
                        }
                        int line = returnStmt.getBegin().map(p -> p.line).orElse(-1);
                        methodModel.returns.add(new ReturnFlow(valueExpr, line));
                    }
                    debugLogger.log("PARSE_METHOD id=%s calls=%d vars=%d", methodModel.id, methodModel.calls.size(), methodModel.visibleVariableTypes.size());

                    classModel.methodsById.put(methodModel.id, methodModel);
                    classModel.methodsByName.computeIfAbsent(methodModel.name, k -> new ArrayList<>()).add(methodModel);
                }

                model.classesByName.put(fqcn, classModel);
                model.simpleToFqcn.computeIfAbsent(simpleName, k -> new LinkedHashSet<>()).add(fqcn);
                debugLogger.log("PARSE_CLASS class=%s methods=%d fields=%d", fqcn, classModel.methodsById.size(), classModel.fields.size());
            }
        }
        debugLogger.log("JAVA_MODEL classes=%d", model.classesByName.size());

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
        List<String> argumentTokens = new ArrayList<String>();
        for (Expression argument : callExpr.getArguments()) {
            String token = toArgumentToken(argument);
            if (!isBlank(token)) {
                argumentTokens.add(token);
            }
        }
        return new CallSite(
                scopeType,
                scopeToken,
                scopeCall,
                callExpr.getNameAsString(),
                callExpr.getArguments().size(),
                line,
                argumentTokens
        );
    }

    private String toArgumentToken(Expression argument) {
        if (argument instanceof NameExpr) {
            return ((NameExpr) argument).getNameAsString();
        }
        if (argument instanceof FieldAccessExpr && ((FieldAccessExpr) argument).getScope() instanceof ThisExpr) {
            return ((FieldAccessExpr) argument).getNameAsString();
        }
        if (argument instanceof ObjectCreationExpr) {
            return "new " + ((ObjectCreationExpr) argument).getType().asString();
        }
        return argument == null ? "" : argument.toString();
    }

    private ValueExpr toValueExpr(Expression expression) {
        if (expression == null) {
            return null;
        }
        if (expression instanceof MethodCallExpr) {
            return ValueExpr.forCall(toCallSite((MethodCallExpr) expression));
        }
        if (expression instanceof NameExpr) {
            return ValueExpr.forVariable(((NameExpr) expression).getNameAsString());
        }
        if (expression instanceof FieldAccessExpr && ((FieldAccessExpr) expression).getScope() instanceof ThisExpr) {
            return ValueExpr.forVariable(((FieldAccessExpr) expression).getNameAsString());
        }
        return null;
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
            if (!shouldAnalyzeXml(xml)) {
                continue;
            }
            Document doc;
            try {
                doc = factory.newDocumentBuilder().parse(xml.toFile());
            } catch (Exception e) {
                continue;
            }
            Element root = doc.getDocumentElement();
            if (root == null || !"beans".equalsIgnoreCase(normalizeXmlTagName(root.getTagName()))) {
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

    private boolean shouldAnalyzeJava(Path javaPath) {
        String normalizedPath = javaPath.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalizedPath.contains("/src/test/") || normalizedPath.contains("/generated-test-sources/")) {
            debugLogger.log("JAVA_FILTER_SKIP file=%s reason=test-source", javaPath.toAbsolutePath());
            return false;
        }

        String fileName = javaPath.getFileName() == null ? "" : javaPath.getFileName().toString();
        boolean excluded = fileName.endsWith("Test.java")
                || fileName.endsWith("Tests.java")
                || fileName.endsWith("IT.java")
                || fileName.endsWith("ITCase.java");
        if (excluded) {
            debugLogger.log("JAVA_FILTER_SKIP file=%s reason=test-name", javaPath.toAbsolutePath());
        }
        return !excluded;
    }

    private boolean shouldAnalyzeXml(Path xmlPath) {
        String fileName = xmlPath.getFileName() == null ? "" : xmlPath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.startsWith("log4j") || fileName.startsWith("logback")) {
            return false;
        }
        return true;
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

    private List<CallEdge> buildCallEdgesFromEntries(JavaModel javaModel,
                                                     Map<String, MethodModel> methodsById,
                                                     InjectionRegistry injectionRegistry,
                                                     Set<String> entryMethodIds,
                                                     int maxDepth) {
        if (entryMethodIds == null || entryMethodIds.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, CallEdgeAccumulator> edgeMap = new LinkedHashMap<String, CallEdgeAccumulator>();
        Map<String, Integer> expandedFrameDepth = new LinkedHashMap<String, Integer>();
        for (String entryMethodId : entryMethodIds) {
            MethodModel entryMethod = methodsById.get(entryMethodId);
            if (entryMethod == null) {
                continue;
            }
            TypeFlowContext rootContext = TypeFlowContext.forRoot(entryMethod.className);
            Deque<String> visitingMethods = new ArrayDeque<String>();
            Set<String> visitingFrames = new LinkedHashSet<String>();
            traverseMethodWithContext(
                    entryMethodId,
                    rootContext,
                    javaModel,
                    methodsById,
                    injectionRegistry,
                    maxDepth,
                    0,
                    visitingMethods,
                    visitingFrames,
                    expandedFrameDepth,
                    edgeMap
            );
        }

        List<CallEdge> edges = new ArrayList<CallEdge>();
        for (CallEdgeAccumulator accumulator : edgeMap.values()) {
            edges.add(new CallEdge(
                    accumulator.from,
                    accumulator.to,
                    String.join("|", accumulator.reasons),
                    accumulator.line,
                    listOf()
            ));
        }
        edges.sort(Comparator.comparing((CallEdge e) -> e.from).thenComparing(e -> e.to));
        debugLogger.log("CALL_GRAPH_ENTRY_SUMMARY entries=%d edges=%d", entryMethodIds.size(), edges.size());
        return edges;
    }

    private void traverseMethodWithContext(String methodId,
                                           TypeFlowContext incomingContext,
                                           JavaModel javaModel,
                                           Map<String, MethodModel> methodsById,
                                           InjectionRegistry injectionRegistry,
                                           int maxDepth,
                                           int depth,
                                           Deque<String> visitingMethods,
                                           Set<String> visitingFrames,
                                           Map<String, Integer> expandedFrameDepth,
                                           Map<String, CallEdgeAccumulator> edgeMap) {
        if (depth > Math.max(maxDepth, 0)) {
            return;
        }
        MethodModel methodModel = methodsById.get(methodId);
        if (methodModel == null) {
            return;
        }
        ClassModel classModel = javaModel.classesByName.get(methodModel.className);
        if (classModel == null) {
            return;
        }

        TypeFlowContext methodContext = initializeMethodContext(classModel, methodModel, incomingContext, javaModel);
        String frameKey = methodId + "@" + methodContext.signature();
        Integer expandedDepth = expandedFrameDepth.get(frameKey);
        if (expandedDepth != null && expandedDepth.intValue() <= depth) {
            return;
        }
        expandedFrameDepth.put(frameKey, Integer.valueOf(depth));

        if (visitingMethods.contains(methodId)) {
            debugLogger.log("TRAVERSE_STOP_CYCLE method=%s depth=%d", methodId, depth);
            return;
        }
        if (!visitingFrames.add(frameKey)) {
            debugLogger.log("TRAVERSE_STOP_FRAME_CYCLE method=%s depth=%d frame=%s", methodId, depth, frameKey);
            return;
        }
        visitingMethods.addLast(methodId);
        try {
            List<CallSite> calls = new ArrayList<CallSite>(methodModel.calls);
            calls.sort(Comparator.comparingInt(c -> c.line <= 0 ? Integer.MAX_VALUE : c.line));
            List<AssignmentFlow> assignments = new ArrayList<AssignmentFlow>(methodModel.assignments);
            assignments.sort(Comparator.comparingInt(a -> a.line <= 0 ? Integer.MAX_VALUE : a.line));
            int assignmentIdx = 0;

            for (CallSite call : calls) {
                assignmentIdx = applyAssignmentsBeforeCall(
                        assignments,
                        assignmentIdx,
                        call.line,
                        classModel,
                        methodModel,
                        methodContext,
                        javaModel,
                        methodsById,
                        injectionRegistry
                );
                List<ResolvedCallTarget> candidates = resolveCallTargetsWithContext(
                        classModel,
                        methodModel,
                        call,
                        methodContext,
                        javaModel,
                        methodsById,
                        injectionRegistry,
                        0
                );

                Map<String, String> terminalCandidateMap = new LinkedHashMap<String, String>();
                for (ResolvedCallTarget candidate : candidates) {
                    terminalCandidateMap.merge(
                            candidate.methodId,
                            candidate.reason,
                            (a, b) -> a.equals(b) ? a : a + "|" + b
                    );
                }
                Map<String, String> pipelineCandidates = resolvePipelineAssemblyCandidates(
                        classModel,
                        methodModel,
                        call,
                        javaModel,
                        methodsById,
                        injectionRegistry,
                        terminalCandidateMap
                );
                for (Map.Entry<String, String> pipelineCandidate : pipelineCandidates.entrySet()) {
                    MethodIdInfo targetInfo = MethodIdInfo.parse(pipelineCandidate.getKey());
                    String receiverClass = targetInfo == null ? "" : targetInfo.className;
                    candidates.add(new ResolvedCallTarget(
                            pipelineCandidate.getKey(),
                            pipelineCandidate.getValue(),
                            receiverClass
                    ));
                }
                candidates = deduplicateResolvedTargets(candidates);

                if (candidates.isEmpty()) {
                    String stopReason = detectUnsupportedStopReason(call);
                    if (!isBlank(stopReason)) {
                        String reason = enrichReasonWithEvidence("STOP:" + stopReason, call);
                        addEdge(edgeMap, methodModel.id, toStopNodeId(stopReason), reason, call.line);
                    }
                    continue;
                }

                for (ResolvedCallTarget candidate : candidates) {
                    String reason = enrichReasonWithEvidence(candidate.reason, call);
                    addEdge(edgeMap, methodModel.id, candidate.methodId, reason, call.line);

                    MethodModel calleeMethod = methodsById.get(candidate.methodId);
                    if (calleeMethod == null) {
                        debugLogger.log("TRAVERSE_STOP_JAR from=%s to=%s reason=%s", methodModel.id, candidate.methodId, reason);
                        continue;
                    }
                    if (depth >= Math.max(maxDepth, 0)) {
                        continue;
                    }
                    TypeFlowContext calleeContext = buildCalleeContext(
                            classModel,
                            methodModel,
                            call,
                            calleeMethod,
                            candidate.receiverClassName,
                            methodContext,
                            javaModel,
                            methodsById,
                            injectionRegistry
                    );
                    traverseMethodWithContext(
                            calleeMethod.id,
                            calleeContext,
                            javaModel,
                            methodsById,
                            injectionRegistry,
                            maxDepth,
                            depth + 1,
                            visitingMethods,
                            visitingFrames,
                            expandedFrameDepth,
                            edgeMap
                    );
                }
            }
        } finally {
            visitingMethods.removeLast();
            visitingFrames.remove(frameKey);
        }
    }

    private int applyAssignmentsBeforeCall(List<AssignmentFlow> assignments,
                                           int assignmentIdx,
                                           int callLine,
                                           ClassModel classModel,
                                           MethodModel methodModel,
                                           TypeFlowContext methodContext,
                                           JavaModel javaModel,
                                           Map<String, MethodModel> methodsById,
                                           InjectionRegistry injectionRegistry) {
        if (assignments == null || assignments.isEmpty()) {
            return assignmentIdx;
        }
        int nextIdx = assignmentIdx;
        while (nextIdx < assignments.size()) {
            AssignmentFlow assignment = assignments.get(nextIdx);
            if (assignment == null) {
                nextIdx++;
                continue;
            }
            if (callLine > 0 && assignment.line > 0 && assignment.line > callLine) {
                break;
            }
            Set<String> inferredTypes = inferValueExprTypes(
                    classModel,
                    methodModel,
                    assignment.value,
                    methodContext,
                    javaModel,
                    methodsById,
                    injectionRegistry
            );
            if (!inferredTypes.isEmpty()) {
                methodContext.variableTypes.put(assignment.targetVar, inferredTypes);
                debugLogger.log(
                        "TYPE_FLOW_ASSIGN method=%s var=%s line=%d types=%s",
                        methodModel.id,
                        assignment.targetVar,
                        assignment.line,
                        inferredTypes
                );
            }
            nextIdx++;
        }
        return nextIdx;
    }

    private Set<String> inferValueExprTypes(ClassModel classModel,
                                            MethodModel methodModel,
                                            ValueExpr valueExpr,
                                            TypeFlowContext methodContext,
                                            JavaModel javaModel,
                                            Map<String, MethodModel> methodsById,
                                            InjectionRegistry injectionRegistry) {
        if (valueExpr == null) {
            return Collections.emptySet();
        }
        if (valueExpr.isVariable()) {
            Set<String> fromContext = methodContext.variableTypes.get(valueExpr.variableName);
            if (fromContext != null && !fromContext.isEmpty()) {
                return new LinkedHashSet<String>(fromContext);
            }
            String localType = methodModel.visibleVariableTypes.get(valueExpr.variableName);
            if (!isBlank(localType)) {
                return resolveClassesByTypeWithContext(localType, javaModel, classModel);
            }
            FieldModel fieldModel = classModel.fields.get(valueExpr.variableName);
            if (fieldModel != null && !isBlank(fieldModel.typeName)) {
                return resolveClassesByTypeWithContext(fieldModel.typeName, javaModel, classModel);
            }
            return Collections.emptySet();
        }
        if (!valueExpr.isCall()) {
            return Collections.emptySet();
        }
        List<ResolvedCallTarget> targets = resolveCallTargetsWithContext(
                classModel,
                methodModel,
                valueExpr.callSite,
                methodContext,
                javaModel,
                methodsById,
                injectionRegistry,
                0
        );
        Set<String> returnTypes = new LinkedHashSet<String>();
        for (ResolvedCallTarget target : targets) {
            returnTypes.addAll(inferMethodReturnTypes(
                    target.methodId,
                    target.receiverClassName,
                    methodContext,
                    javaModel,
                    methodsById
            ));
        }
        return returnTypes;
    }

    private TypeFlowContext buildCalleeContext(ClassModel callerClass,
                                               MethodModel callerMethod,
                                               CallSite call,
                                               MethodModel calleeMethod,
                                               String receiverClassName,
                                               TypeFlowContext callerContext,
                                               JavaModel javaModel,
                                               Map<String, MethodModel> methodsById,
                                               InjectionRegistry injectionRegistry) {
        TypeFlowContext calleeContext = new TypeFlowContext();
        if (!isBlank(receiverClassName)) {
            calleeContext.thisTypeNames.add(receiverClassName);
        } else {
            calleeContext.thisTypeNames.add(calleeMethod.className);
        }
        for (int i = 0; i < calleeMethod.parameterNames.size(); i++) {
            String parameterName = calleeMethod.parameterNames.get(i);
            if (isBlank(parameterName)) {
                continue;
            }
            Set<String> types = Collections.emptySet();
            if (i < call.argumentTokens.size()) {
                String token = normalizePipelineArgToken(call.argumentTokens.get(i));
                types = inferTokenTypes(
                        callerClass,
                        callerMethod,
                        token,
                        callerContext,
                        javaModel,
                        methodsById,
                        injectionRegistry
                );
            }
            if (types.isEmpty()) {
                String parameterType = calleeMethod.visibleVariableTypes.get(parameterName);
                if (!isBlank(parameterType)) {
                    types = resolveClassesByTypeWithContext(parameterType, javaModel, javaModel.classesByName.get(calleeMethod.className));
                }
            }
            if (!types.isEmpty()) {
                calleeContext.variableTypes.put(parameterName, new LinkedHashSet<String>(types));
            }
        }
        return calleeContext;
    }

    private TypeFlowContext initializeMethodContext(ClassModel classModel,
                                                    MethodModel methodModel,
                                                    TypeFlowContext incomingContext,
                                                    JavaModel javaModel) {
        TypeFlowContext context = new TypeFlowContext();
        if (incomingContext != null) {
            for (Map.Entry<String, Set<String>> entry : incomingContext.variableTypes.entrySet()) {
                context.variableTypes.put(entry.getKey(), new LinkedHashSet<String>(entry.getValue()));
            }
            context.thisTypeNames.addAll(incomingContext.thisTypeNames);
        }
        if (context.thisTypeNames.isEmpty()) {
            context.thisTypeNames.add(classModel.fqcn);
        }
        for (String parameterName : methodModel.parameterNames) {
            if (isBlank(parameterName)) {
                continue;
            }
            if (context.variableTypes.containsKey(parameterName)) {
                continue;
            }
            String declaredType = methodModel.visibleVariableTypes.get(parameterName);
            if (isBlank(declaredType)) {
                continue;
            }
            Set<String> candidates = resolveClassesByTypeWithContext(declaredType, javaModel, classModel);
            if (!candidates.isEmpty()) {
                context.variableTypes.put(parameterName, candidates);
            }
        }
        return context;
    }

    private List<ResolvedCallTarget> resolveCallTargetsWithContext(ClassModel classModel,
                                                                   MethodModel methodModel,
                                                                   CallSite call,
                                                                   TypeFlowContext context,
                                                                   JavaModel javaModel,
                                                                   Map<String, MethodModel> methodsById,
                                                                   InjectionRegistry injectionRegistry,
                                                                   int depth) {
        if (depth > CALL_RESOLVE_MAX_DEPTH) {
            debugLogger.log("RESOLVE_DEPTH_GUARD from=%s call=%s/%d", methodModel.id, call.methodName, call.argumentCount);
            return Collections.emptyList();
        }
        List<ResolvedCallTarget> resolved = new ArrayList<ResolvedCallTarget>();

        if (call.scopeType == ScopeType.UNSCOPED || call.scopeType == ScopeType.THIS) {
            Set<String> thisTypes = context == null || context.thisTypeNames.isEmpty()
                    ? Collections.<String>singleton(classModel.fqcn)
                    : context.thisTypeNames;
            for (String thisType : thisTypes) {
                addResolvedTargets(
                        resolved,
                        resolveMethodByReceiverDispatch(thisType, call, javaModel),
                        "JAVA:this",
                        thisType
                );
            }
            if (context == null || context.thisTypeNames.isEmpty()) {
                addResolvedTargets(
                        resolved,
                        resolveMethodInDescendants(classModel, call, javaModel),
                        "JAVA:this-override",
                        classModel.fqcn
                );
            }
            return deduplicateResolvedTargets(resolved);
        }

        if (call.scopeType == ScopeType.NAME && call.scopeToken != null) {
            Set<String> ownerTypes = context == null || context.thisTypeNames.isEmpty()
                    ? Collections.<String>singleton(classModel.fqcn)
                    : context.thisTypeNames;
            boolean resolvedByInjection = false;
            for (String ownerType : ownerTypes) {
                Map<String, String> injectedTargets = resolveInjectionTargetsByClassHierarchy(
                        ownerType,
                        call.scopeToken,
                        javaModel,
                        injectionRegistry
                );
                if (injectedTargets.isEmpty()) {
                    continue;
                }
                resolvedByInjection = true;
                for (Map.Entry<String, String> target : injectedTargets.entrySet()) {
                    addResolvedTargets(
                            resolved,
                            resolveMethodByReceiverDispatch(target.getKey(), call, javaModel),
                            target.getValue(),
                            target.getKey()
                    );
                }
            }
            if (resolvedByInjection) {
                return deduplicateResolvedTargets(resolved);
            }

            Set<String> runtimeTypes = context == null ? Collections.<String>emptySet()
                    : context.variableTypes.getOrDefault(call.scopeToken, Collections.<String>emptySet());
            if (runtimeTypes != null && !runtimeTypes.isEmpty()) {
                for (String runtimeType : runtimeTypes) {
                    addResolvedTargets(
                            resolved,
                            resolveMethodByReceiverDispatch(runtimeType, call, javaModel),
                            "CTX:runtime-var",
                            runtimeType
                    );
                }
                if (!resolved.isEmpty()) {
                    return deduplicateResolvedTargets(resolved);
                }
            }

            String localType = methodModel.visibleVariableTypes.get(call.scopeToken);
            if (!isBlank(localType)) {
                Set<String> localCandidates = resolveClassesByTypeWithContext(localType, javaModel, classModel);
                for (String localCandidate : localCandidates) {
                    addResolvedTargets(resolved, resolveMethodByReceiverDispatch(localCandidate, call, javaModel), "JAVA:local-var", localCandidate);
                }
                if (!resolved.isEmpty()) {
                    return deduplicateResolvedTargets(resolved);
                }
            }

            FieldModel fieldModel = classModel.fields.get(call.scopeToken);
            if (fieldModel != null && !isBlank(fieldModel.typeName)) {
                Set<String> typeCandidates = resolveClassesByTypeWithContext(fieldModel.typeName, javaModel, classModel);
                for (String typeCandidate : typeCandidates) {
                    addResolvedTargets(resolved, resolveMethodByReceiverDispatch(typeCandidate, call, javaModel), "JAVA:field-type", typeCandidate);
                }
                if (!resolved.isEmpty()) {
                    return deduplicateResolvedTargets(resolved);
                }
            }

            if (isLikelyTypeReferenceToken(call.scopeToken)) {
                Set<String> staticTypeCandidates = resolveClassesByTypeWithContext(call.scopeToken, javaModel, classModel);
                for (String staticTypeCandidate : staticTypeCandidates) {
                    addResolvedTargets(
                            resolved,
                            resolveMethodInClassHierarchy(staticTypeCandidate, call, javaModel),
                            "JAVA:static-type",
                            staticTypeCandidate
                    );
                }
            }
            return deduplicateResolvedTargets(resolved);
        }

        if (call.scopeType == ScopeType.METHOD_CALL && call.scopeCall != null) {
            List<ResolvedCallTarget> scopeCalls = resolveCallTargetsWithContext(
                    classModel,
                    methodModel,
                    call.scopeCall,
                    context,
                    javaModel,
                    methodsById,
                    injectionRegistry,
                    depth + 1
            );
            for (ResolvedCallTarget scopeCall : scopeCalls) {
                Set<String> receiverTypes = inferMethodReturnTypes(
                        scopeCall.methodId,
                        scopeCall.receiverClassName,
                        context,
                        javaModel,
                        methodsById
                );
                if (receiverTypes.isEmpty() && !isBlank(scopeCall.receiverClassName)) {
                    receiverTypes.add(scopeCall.receiverClassName);
                }
                if (receiverTypes.isEmpty()) {
                    continue;
                }
                for (String receiverType : receiverTypes) {
                    addResolvedTargets(
                            resolved,
                            resolveMethodByReceiverDispatch(receiverType, call, javaModel),
                            "CHAIN:" + scopeCall.reason,
                            receiverType
                    );
                }
            }
            return deduplicateResolvedTargets(resolved);
        }
        return Collections.emptyList();
    }

    private void addResolvedTargets(List<ResolvedCallTarget> resolved,
                                    Set<String> targets,
                                    String reason,
                                    String receiverClassName) {
        for (String target : targets) {
            resolved.add(new ResolvedCallTarget(target, reason, receiverClassName));
        }
    }

    private List<ResolvedCallTarget> deduplicateResolvedTargets(List<ResolvedCallTarget> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, ResolvedCallTarget> unique = new LinkedHashMap<String, ResolvedCallTarget>();
        for (ResolvedCallTarget target : raw) {
            if (target == null || isBlank(target.methodId)) {
                continue;
            }
            String key = target.methodId + "#" + target.receiverClassName;
            ResolvedCallTarget existing = unique.get(key);
            if (existing == null) {
                unique.put(key, target);
                continue;
            }
            String mergedReason = existing.reason.equals(target.reason)
                    ? existing.reason
                    : existing.reason + "|" + target.reason;
            unique.put(key, new ResolvedCallTarget(existing.methodId, mergedReason, existing.receiverClassName));
        }
        return new ArrayList<ResolvedCallTarget>(unique.values());
    }

    private Set<String> inferMethodReturnTypes(String methodId,
                                               String receiverClassName,
                                               TypeFlowContext callContext,
                                               JavaModel javaModel,
                                               Map<String, MethodModel> methodsById) {
        MethodModel methodModel = methodsById.get(methodId);
        MethodIdInfo idInfo = MethodIdInfo.parse(methodId);
        String ownerClass = methodModel != null ? methodModel.className : (idInfo == null ? "" : idInfo.className);
        if (methodModel == null && !isBlank(ownerClass)) {
            return setOf(ownerClass);
        }
        if (methodModel == null) {
            return Collections.emptySet();
        }

        ClassModel ownerClassModel = javaModel.classesByName.get(methodModel.className);
        Set<String> inferred = new LinkedHashSet<String>();
        String returnType = normalizeTypeName(methodModel.returnTypeName);
        if (!isBlank(returnType) && !"void".equals(returnType)) {
            inferred.addAll(resolveClassesByTypeWithContext(returnType, javaModel, ownerClassModel));
        }
        if (inferred.isEmpty() || isLikelyTypeVariable(returnType)) {
            if (!isBlank(receiverClassName)) {
                inferred.add(receiverClassName);
            }
            inferred.add(methodModel.className);
        }
        if (!methodModel.returns.isEmpty() && callContext != null) {
            for (ReturnFlow returnFlow : methodModel.returns) {
                if (returnFlow == null || returnFlow.value == null) {
                    continue;
                }
                if (returnFlow.value.isVariable()) {
                    Set<String> types = callContext.variableTypes.get(returnFlow.value.variableName);
                    if (types != null && !types.isEmpty()) {
                        inferred.addAll(types);
                    }
                }
            }
        }
        return inferred;
    }

    private Set<String> inferTokenTypes(ClassModel classModel,
                                        MethodModel methodModel,
                                        String token,
                                        TypeFlowContext context,
                                        JavaModel javaModel,
                                        Map<String, MethodModel> methodsById,
                                        InjectionRegistry injectionRegistry) {
        if (isBlank(token)) {
            return Collections.emptySet();
        }
        if (token.startsWith("new ")) {
            String createdType = normalizeTypeName(token.substring("new ".length()).trim());
            return resolveClassesByTypeWithContext(createdType, javaModel, classModel);
        }
        Set<String> runtimeTypes = context == null
                ? Collections.<String>emptySet()
                : context.variableTypes.getOrDefault(token, Collections.<String>emptySet());
        if (!runtimeTypes.isEmpty()) {
            return new LinkedHashSet<String>(runtimeTypes);
        }
        String localType = methodModel.visibleVariableTypes.get(token);
        if (!isBlank(localType)) {
            return resolveClassesByTypeWithContext(localType, javaModel, classModel);
        }
        FieldModel fieldModel = classModel.fields.get(token);
        if (fieldModel != null && !isBlank(fieldModel.typeName)) {
            Set<String> injectedTargets = new LinkedHashSet<String>();
            Map<String, String> byInjection = resolveInjectionTargetsByClassHierarchy(
                    classModel.fqcn,
                    token,
                    javaModel,
                    injectionRegistry
            );
            if (!byInjection.isEmpty()) {
                injectedTargets.addAll(byInjection.keySet());
            }
            if (!injectedTargets.isEmpty()) {
                return injectedTargets;
            }
            return resolveClassesByTypeWithContext(fieldModel.typeName, javaModel, classModel);
        }
        if (isLikelyTypeReferenceToken(token)) {
            return resolveClassesByTypeWithContext(token, javaModel, classModel);
        }
        return Collections.emptySet();
    }

    private String enrichReasonWithEvidence(String reason, CallSite call) {
        String mergedReason = isBlank(reason) ? "JAVA:unknown" : reason;
        StringBuilder evidence = new StringBuilder();
        evidence.append("EVIDENCE:call=").append(call.methodName).append("/").append(call.argumentCount);
        evidence.append(",scope=").append(call.scopeType);
        if (!isBlank(call.scopeToken)) {
            evidence.append(",token=").append(call.scopeToken);
        }
        if (call.line > 0) {
            evidence.append(",line=").append(call.line);
        }
        if (mergedReason.contains(evidence.toString())) {
            return mergedReason;
        }
        return mergedReason + "|" + evidence.toString();
    }

    private String detectUnsupportedStopReason(CallSite call) {
        if (call == null || isBlank(call.methodName)) {
            return "";
        }
        String methodName = call.methodName.toLowerCase(Locale.ROOT);
        if ("forname".equals(methodName)
                || "loadclass".equals(methodName)
                || "getmethod".equals(methodName)
                || "getdeclaredmethod".equals(methodName)
                || "invoke".equals(methodName)
                || "newproxyinstance".equals(methodName)) {
            return "REFLECTION_OR_PROXY";
        }
        if ("load".equals(methodName) && "ServiceLoader".equals(call.scopeToken)) {
            return "SPI";
        }
        if ("loadlibrary".equals(methodName) || "load".equals(methodName) && "System".equals(call.scopeToken)) {
            return "NATIVE";
        }
        return "";
    }

    private String toStopNodeId(String stopReason) {
        return "STOP::" + (isBlank(stopReason) ? "UNKNOWN" : stopReason);
    }

    private List<CallEdge> buildCallEdges(JavaModel javaModel, Map<String, MethodModel> methodsById, InjectionRegistry injectionRegistry) {
        Map<String, CallEdgeAccumulator> edgeMap = new LinkedHashMap<>();
        int totalCallSites = 0;
        int resolvedCallSites = 0;
        int unresolvedCallSites = 0;

        for (ClassModel classModel : javaModel.classesByName.values()) {
            for (MethodModel methodModel : classModel.methodsById.values()) {
                for (CallSite call : methodModel.calls) {
                    totalCallSites++;
                    Map<String, String> candidates = resolveCallCandidates(
                            classModel,
                            methodModel,
                            call,
                            javaModel,
                            methodsById,
                            injectionRegistry,
                            0
                    );
                    if (candidates.isEmpty()) {
                        unresolvedCallSites++;
                        debugLogger.log(
                                "RESOLVE_CALL_EMPTY from=%s line=%d call=%s/%d scope=%s scopeToken=%s",
                                methodModel.id,
                                call.line,
                                call.methodName,
                                call.argumentCount,
                                call.scopeType,
                                call.scopeToken
                        );
                    } else {
                        resolvedCallSites++;
                        debugLogger.log(
                                "RESOLVE_CALL from=%s line=%d call=%s/%d targets=%d",
                                methodModel.id,
                                call.line,
                                call.methodName,
                                call.argumentCount,
                                candidates.size()
                        );
                    }
                    for (Map.Entry<String, String> candidate : candidates.entrySet()) {
                        addEdge(edgeMap, methodModel.id, candidate.getKey(), candidate.getValue(), call.line);
                    }

                    Map<String, String> pipelineCandidates = resolvePipelineAssemblyCandidates(
                            classModel,
                            methodModel,
                            call,
                            javaModel,
                            methodsById,
                            injectionRegistry,
                            candidates
                    );
                    for (Map.Entry<String, String> candidate : pipelineCandidates.entrySet()) {
                        addEdge(edgeMap, methodModel.id, candidate.getKey(), candidate.getValue(), call.line);
                    }
                }
            }
        }

        List<CallEdge> edges = new ArrayList<CallEdge>();
        for (CallEdgeAccumulator accumulator : edgeMap.values()) {
            edges.add(new CallEdge(
                    accumulator.from,
                    accumulator.to,
                    String.join("|", accumulator.reasons),
                    accumulator.line,
                    listOf()
            ));
        }
        edges.sort(Comparator.comparing((CallEdge e) -> e.from).thenComparing(e -> e.to));
        debugLogger.log(
                "CALL_GRAPH_SUMMARY callSites=%d resolvedCallSites=%d unresolvedCallSites=%d edges=%d",
                totalCallSites,
                resolvedCallSites,
                unresolvedCallSites,
                edges.size()
        );
        return edges;
    }

    private void addEdge(Map<String, CallEdgeAccumulator> edgeMap,
                         String fromMethodId,
                         String toMethodId,
                         String reason,
                         int line) {
        String key = fromMethodId + "->" + toMethodId;
        CallEdgeAccumulator accumulator = edgeMap.get(key);
        if (accumulator == null) {
            accumulator = new CallEdgeAccumulator(fromMethodId, toMethodId, line);
            edgeMap.put(key, accumulator);
        } else {
            accumulator.occurrences++;
            debugLogger.log(
                    "EDGE_DEDUP from=%s to=%s line=%d occurrences=%d",
                    accumulator.from,
                    accumulator.to,
                    line,
                    accumulator.occurrences
            );
        }
        accumulator.reasons.add(reason);
        debugLogger.log(
                "EDGE_ADD from=%s to=%s reason=%s line=%d",
                fromMethodId,
                toMethodId,
                reason,
                line
        );
    }

    private Map<String, String> resolvePipelineAssemblyCandidates(ClassModel classModel,
                                                                  MethodModel methodModel,
                                                                  CallSite call,
                                                                  JavaModel javaModel,
                                                                  Map<String, MethodModel> methodsById,
                                                                  InjectionRegistry injectionRegistry,
                                                                  Map<String, String> terminalCandidates) {
        if (call.scopeCall == null || terminalCandidates.isEmpty()) {
            return Collections.emptyMap();
        }
        boolean namedTerminal = isPipelineTerminalCall(call);
        PipelineStepContext stepContext = collectPipelineStepContext(
                terminalCandidates.keySet(),
                methodsById,
                javaModel
        );
        Set<String> expectedStepTypes = stepContext.receiverTypeNames;
        Set<PipelineStepCallSignature> stepSignatures = stepContext.signatures;
        if (!namedTerminal && stepSignatures.isEmpty()) {
            return Collections.emptyMap();
        }
        if (stepSignatures.isEmpty()) {
            return Collections.emptyMap();
        }

        List<PipelineAssemblyArg> assemblyArgs = new ArrayList<PipelineAssemblyArg>();
        assemblyArgs.addAll(collectPipelineAssemblyArgs(
                classModel,
                methodModel,
                call.scopeCall,
                javaModel,
                methodsById,
                injectionRegistry,
                expectedStepTypes
        ));
        assemblyArgs.addAll(collectPipelineAssemblyArgsFromScopeMethods(
                classModel,
                methodModel,
                call,
                javaModel,
                methodsById,
                injectionRegistry,
                expectedStepTypes
        ));
        assemblyArgs = deduplicatePipelineAssemblyArgs(assemblyArgs);
        if (assemblyArgs.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> assemblyTargets = resolvePipelineAssemblyTargets(
                assemblyArgs,
                javaModel,
                methodsById,
                injectionRegistry
        );
        if (assemblyTargets.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> resolved = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> stageTarget : assemblyTargets.entrySet()) {
            for (PipelineStepCallSignature signature : stepSignatures) {
                CallSite syntheticCall = new CallSite(
                        ScopeType.NAME,
                        null,
                        null,
                        signature.methodName,
                        signature.argumentCount,
                        call.line,
                        listOf()
                );
                Set<String> targets = resolveMethodInClassHierarchy(stageTarget.getKey(), syntheticCall, javaModel);
                for (String target : targets) {
                    MethodIdInfo targetInfo = MethodIdInfo.parse(target);
                    if (targetInfo != null) {
                        ClassModel targetClassModel = javaModel.classesByName.get(targetInfo.className);
                        if (targetClassModel != null && targetClassModel.isInterface) {
                            continue;
                        }
                    }
                    String reason = "PIPELINE:" + stageTarget.getValue();
                    resolved.merge(target, reason, (a, b) -> a.equals(b) ? a : a + "|" + b);
                    debugLogger.log(
                            "PIPELINE_EDGE from=%s terminal=%s stageClass=%s call=%s/%d target=%s reason=%s",
                            methodModel.id,
                            call.methodName,
                            stageTarget.getKey(),
                            signature.methodName,
                            signature.argumentCount,
                            target,
                            reason
                    );
                }
            }
        }
        return resolved;
    }

    private boolean isPipelineTerminalCall(CallSite call) {
        if (call == null || isBlank(call.methodName)) {
            return false;
        }
        return PIPELINE_TERMINAL_METHOD_NAMES.contains(call.methodName.toLowerCase(Locale.ROOT));
    }

    private List<PipelineAssemblyArg> collectPipelineAssemblyArgs(ClassModel ownerClass,
                                                                  MethodModel ownerMethod,
                                                                  CallSite scopeCall,
                                                                  JavaModel javaModel,
                                                                  Map<String, MethodModel> methodsById,
                                                                  InjectionRegistry injectionRegistry,
                                                                  Set<String> expectedStepTypes) {
        List<PipelineAssemblyArg> args = new ArrayList<PipelineAssemblyArg>();
        CallSite current = scopeCall;
        while (current != null) {
            if (isPipelineAssemblyCallSite(
                    ownerClass,
                    ownerMethod,
                    current,
                    javaModel,
                    methodsById,
                    injectionRegistry,
                    expectedStepTypes
            )) {
                for (String token : current.argumentTokens) {
                    String normalized = normalizePipelineArgToken(token);
                    if (!isBlank(normalized)) {
                        args.add(new PipelineAssemblyArg(
                                normalized,
                                ownerClass == null ? "" : ownerClass.fqcn,
                                ownerMethod == null ? "" : ownerMethod.id,
                                current.line
                        ));
                    }
                }
            }
            current = current.scopeCall;
        }
        return args;
    }

    private List<PipelineAssemblyArg> collectPipelineAssemblyArgsFromScopeMethods(ClassModel classModel,
                                                                                  MethodModel methodModel,
                                                                                  CallSite call,
                                                                                  JavaModel javaModel,
                                                                                  Map<String, MethodModel> methodsById,
                                                                                  InjectionRegistry injectionRegistry,
                                                                                  Set<String> expectedStepTypes) {
        if (call.scopeCall == null) {
            return Collections.emptyList();
        }
        Map<String, String> scopeCandidates = resolveCallCandidates(
                classModel,
                methodModel,
                call.scopeCall,
                javaModel,
                methodsById,
                injectionRegistry,
                0
        );
        if (scopeCandidates.isEmpty()) {
            return Collections.emptyList();
        }

        List<PipelineAssemblyArg> args = new ArrayList<PipelineAssemblyArg>();
        Set<String> visitedMethodIds = new LinkedHashSet<String>();
        for (String scopeMethodId : scopeCandidates.keySet()) {
            collectPipelineAssemblyArgsFromMethodReturn(
                    scopeMethodId,
                    javaModel,
                    methodsById,
                    injectionRegistry,
                    expectedStepTypes,
                    0,
                    visitedMethodIds,
                    args
            );
        }
        return args;
    }

    private void collectPipelineAssemblyArgsFromMethodReturn(String methodId,
                                                             JavaModel javaModel,
                                                             Map<String, MethodModel> methodsById,
                                                             InjectionRegistry injectionRegistry,
                                                             Set<String> expectedStepTypes,
                                                             int depth,
                                                             Set<String> visiting,
                                                             List<PipelineAssemblyArg> out) {
        if (depth > FLOW_TRACE_MAX_DEPTH || !visiting.add(methodId)) {
            return;
        }
        MethodModel methodModel = methodsById.get(methodId);
        if (methodModel == null) {
            visiting.remove(methodId);
            return;
        }
        ClassModel classModel = javaModel.classesByName.get(methodModel.className);
        if (classModel == null) {
            visiting.remove(methodId);
            return;
        }

        Set<String> variableVisit = new LinkedHashSet<String>();
        if (methodModel.returns.isEmpty()) {
            // Fallback: if no explicit return expression is captured, use method body calls as approximation.
            for (CallSite callSite : methodModel.calls) {
                collectPipelineAssemblyArgsFromCallSite(
                        classModel,
                        methodModel,
                        callSite,
                        Integer.MAX_VALUE,
                        javaModel,
                        methodsById,
                        injectionRegistry,
                        expectedStepTypes,
                        depth,
                        visiting,
                        variableVisit,
                        out
                );
            }
        } else {
            for (ReturnFlow returnFlow : methodModel.returns) {
                collectPipelineAssemblyArgsFromValueExpr(
                        classModel,
                        methodModel,
                        returnFlow.value,
                        returnFlow.line <= 0 ? Integer.MAX_VALUE : returnFlow.line,
                        javaModel,
                        methodsById,
                        injectionRegistry,
                        expectedStepTypes,
                        depth,
                        visiting,
                        variableVisit,
                        out
                );
            }
        }
        visiting.remove(methodId);
    }

    private void collectPipelineAssemblyArgsFromValueExpr(ClassModel classModel,
                                                          MethodModel methodModel,
                                                          ValueExpr valueExpr,
                                                          int lineLimit,
                                                          JavaModel javaModel,
                                                          Map<String, MethodModel> methodsById,
                                                          InjectionRegistry injectionRegistry,
                                                          Set<String> expectedStepTypes,
                                                          int depth,
                                                          Set<String> visitingMethods,
                                                          Set<String> visitingVariables,
                                                          List<PipelineAssemblyArg> out) {
        if (valueExpr == null) {
            return;
        }
        if (valueExpr.isCall()) {
            collectPipelineAssemblyArgsFromCallSite(
                    classModel,
                    methodModel,
                    valueExpr.callSite,
                    lineLimit,
                    javaModel,
                    methodsById,
                    injectionRegistry,
                    expectedStepTypes,
                    depth,
                    visitingMethods,
                    visitingVariables,
                    out
            );
            return;
        }
        if (valueExpr.isVariable()) {
            collectPipelineAssemblyArgsFromVariableState(
                    classModel,
                    methodModel,
                    valueExpr.variableName,
                    lineLimit,
                    javaModel,
                    methodsById,
                    injectionRegistry,
                    expectedStepTypes,
                    depth,
                    visitingMethods,
                    visitingVariables,
                    out
            );
        }
    }

    private void collectPipelineAssemblyArgsFromCallSite(ClassModel classModel,
                                                         MethodModel methodModel,
                                                         CallSite callSite,
                                                         int lineLimit,
                                                         JavaModel javaModel,
                                                         Map<String, MethodModel> methodsById,
                                                         InjectionRegistry injectionRegistry,
                                                         Set<String> expectedStepTypes,
                                                         int depth,
                                                         Set<String> visitingMethods,
                                                         Set<String> visitingVariables,
                                                         List<PipelineAssemblyArg> out) {
        if (callSite == null) {
            return;
        }
        if (callSite.line > 0 && callSite.line > lineLimit) {
            return;
        }
        if (callSite.scopeType == ScopeType.METHOD_CALL && callSite.scopeCall != null) {
            out.addAll(collectPipelineAssemblyArgs(
                    classModel,
                    methodModel,
                    callSite.scopeCall,
                    javaModel,
                    methodsById,
                    injectionRegistry,
                    expectedStepTypes
            ));
        }

        boolean assemblyLikeCall = isPipelineAssemblyCallSite(
                classModel,
                methodModel,
                callSite,
                javaModel,
                methodsById,
                injectionRegistry,
                expectedStepTypes
        );
        if (assemblyLikeCall) {
            for (String token : callSite.argumentTokens) {
                String normalized = normalizePipelineArgToken(token);
                if (!isBlank(normalized)) {
                    out.add(new PipelineAssemblyArg(normalized, classModel.fqcn, methodModel.id, callSite.line));
                    debugLogger.log(
                            "PIPELINE_ASSEMBLY_ARG method=%s owner=%s token=%s",
                            methodModel.id,
                            classModel.fqcn,
                            normalized
                    );
                }
            }
        }

        if (callSite.scopeType == ScopeType.NAME && !isBlank(callSite.scopeToken)) {
            collectPipelineAssemblyArgsFromVariableState(
                    classModel,
                    methodModel,
                    callSite.scopeToken,
                    lineLimit,
                    javaModel,
                    methodsById,
                    injectionRegistry,
                    expectedStepTypes,
                    depth,
                    visitingMethods,
                    visitingVariables,
                    out
            );
        }

        if (assemblyLikeCall) {
            return;
        }

        Map<String, String> targets = resolveCallCandidates(
                classModel,
                methodModel,
                callSite,
                javaModel,
                methodsById,
                injectionRegistry,
                0
        );
        for (String targetMethodId : targets.keySet()) {
            if (!methodsById.containsKey(targetMethodId)) {
                continue;
            }
            collectPipelineAssemblyArgsFromMethodReturn(
                    targetMethodId,
                    javaModel,
                    methodsById,
                    injectionRegistry,
                    expectedStepTypes,
                    depth + 1,
                    visitingMethods,
                    out
            );
        }
    }

    private List<PipelineAssemblyArg> deduplicatePipelineAssemblyArgs(List<PipelineAssemblyArg> rawArgs) {
        if (rawArgs == null || rawArgs.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, PipelineAssemblyArg> unique = new LinkedHashMap<String, PipelineAssemblyArg>();
        for (PipelineAssemblyArg arg : rawArgs) {
            if (arg == null || isBlank(arg.token) || isBlank(arg.ownerClassName)) {
                continue;
            }
            unique.put(arg.ownerClassName + "#" + arg.ownerMethodId + "#" + arg.token, arg);
        }
        return new ArrayList<PipelineAssemblyArg>(unique.values());
    }

    private void collectPipelineAssemblyArgsFromVariableState(ClassModel classModel,
                                                              MethodModel methodModel,
                                                              String variableName,
                                                              int lineLimit,
                                                              JavaModel javaModel,
                                                              Map<String, MethodModel> methodsById,
                                                              InjectionRegistry injectionRegistry,
                                                              Set<String> expectedStepTypes,
                                                              int depth,
                                                              Set<String> visitingMethods,
                                                              Set<String> visitingVariables,
                                                              List<PipelineAssemblyArg> out) {
        if (depth > FLOW_TRACE_MAX_DEPTH) {
            return;
        }
        if (isBlank(variableName)) {
            return;
        }
        String visitKey = methodModel.id + "#" + variableName + "#" + lineLimit;
        if (!visitingVariables.add(visitKey)) {
            return;
        }
        try {
            for (CallSite callSite : methodModel.calls) {
                if (callSite.scopeType != ScopeType.NAME || !variableName.equals(callSite.scopeToken)) {
                    continue;
                }
                if (callSite.line > 0 && callSite.line > lineLimit) {
                    continue;
                }
                boolean assemblyLikeCall = isPipelineAssemblyCallSite(
                        classModel,
                        methodModel,
                        callSite,
                        javaModel,
                        methodsById,
                        injectionRegistry,
                        expectedStepTypes
                );
                if (assemblyLikeCall) {
                    for (String token : callSite.argumentTokens) {
                        String normalized = normalizePipelineArgToken(token);
                        if (!isBlank(normalized)) {
                            out.add(new PipelineAssemblyArg(normalized, classModel.fqcn, methodModel.id, callSite.line));
                            debugLogger.log(
                                    "PIPELINE_ASSEMBLY_ARG method=%s owner=%s token=%s",
                                    methodModel.id,
                                    classModel.fqcn,
                                    normalized
                            );
                        }
                    }
                }
            }

            for (AssignmentFlow assignment : methodModel.assignments) {
                if (!variableName.equals(assignment.targetVar)) {
                    continue;
                }
                if (assignment.line > 0 && assignment.line > lineLimit) {
                    continue;
                }
                collectPipelineAssemblyArgsFromValueExpr(
                        classModel,
                        methodModel,
                        assignment.value,
                        assignment.line <= 0 ? lineLimit : assignment.line,
                        javaModel,
                        methodsById,
                        injectionRegistry,
                        expectedStepTypes,
                        depth,
                        visitingMethods,
                        visitingVariables,
                        out
                );
            }

            // If the tracked variable is passed as an argument, continue tracing in callee parameter scope.
            for (CallSite callSite : methodModel.calls) {
                if (callSite.line > 0 && callSite.line > lineLimit) {
                    continue;
                }
                List<Integer> boundIndexes = resolveArgumentIndexes(callSite.argumentTokens, variableName);
                if (boundIndexes.isEmpty()) {
                    continue;
                }
                Map<String, String> targets = resolveCallCandidates(
                        classModel,
                        methodModel,
                        callSite,
                        javaModel,
                        methodsById,
                        injectionRegistry,
                        0
                );
                for (String targetMethodId : targets.keySet()) {
                    MethodModel calleeMethod = methodsById.get(targetMethodId);
                    if (calleeMethod == null || calleeMethod.parameterNames.isEmpty()) {
                        continue;
                    }
                    ClassModel calleeClass = javaModel.classesByName.get(calleeMethod.className);
                    if (calleeClass == null) {
                        continue;
                    }
                    for (Integer index : boundIndexes) {
                        if (index == null || index.intValue() < 0 || index.intValue() >= calleeMethod.parameterNames.size()) {
                            continue;
                        }
                        String calleeParam = calleeMethod.parameterNames.get(index.intValue());
                        if (isBlank(calleeParam)) {
                            continue;
                        }
                        collectPipelineAssemblyArgsFromVariableState(
                                calleeClass,
                                calleeMethod,
                                calleeParam,
                                Integer.MAX_VALUE,
                                javaModel,
                                methodsById,
                                injectionRegistry,
                                expectedStepTypes,
                                depth + 1,
                                visitingMethods,
                                visitingVariables,
                                out
                        );
                    }
                }
            }
        } finally {
            visitingVariables.remove(visitKey);
        }
    }

    private List<Integer> resolveArgumentIndexes(List<String> argumentTokens, String variableName) {
        if (argumentTokens == null || argumentTokens.isEmpty() || isBlank(variableName)) {
            return Collections.emptyList();
        }
        List<Integer> indexes = new ArrayList<Integer>();
        for (int i = 0; i < argumentTokens.size(); i++) {
            String token = normalizePipelineArgToken(argumentTokens.get(i));
            if (isBlank(token)) {
                continue;
            }
            if (variableName.equals(token)) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    private Map<String, String> resolvePipelineAssemblyTargets(List<PipelineAssemblyArg> assemblyArgs,
                                                               JavaModel javaModel,
                                                               Map<String, MethodModel> methodsById,
                                                               InjectionRegistry injectionRegistry) {
        if (assemblyArgs.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> resolved = new LinkedHashMap<String, String>();
        Map<String, List<CallerBinding>> callerBindingsCache = new LinkedHashMap<String, List<CallerBinding>>();
        Set<String> visitingArgs = new LinkedHashSet<String>();
        for (PipelineAssemblyArg arg : assemblyArgs) {
            Map<String, String> argTargets = resolvePipelineAssemblyTargetsFromArg(
                    arg,
                    javaModel,
                    methodsById,
                    injectionRegistry,
                    callerBindingsCache,
                    visitingArgs,
                    0
            );
            for (Map.Entry<String, String> target : argTargets.entrySet()) {
                resolved.merge(target.getKey(), target.getValue(), (a, b) -> a.equals(b) ? a : a + "|" + b);
            }
        }
        return resolved;
    }

    private Map<String, String> resolvePipelineAssemblyTargetsFromArg(PipelineAssemblyArg arg,
                                                                      JavaModel javaModel,
                                                                      Map<String, MethodModel> methodsById,
                                                                      InjectionRegistry injectionRegistry,
                                                                      Map<String, List<CallerBinding>> callerBindingsCache,
                                                                      Set<String> visitingArgs,
                                                                      int depth) {
        if (arg == null || isBlank(arg.token) || isBlank(arg.ownerClassName) || depth > FLOW_TRACE_MAX_DEPTH) {
            return Collections.emptyMap();
        }
        String visitKey = arg.ownerClassName + "#" + arg.ownerMethodId + "#" + arg.token + "#" + arg.line;
        if (!visitingArgs.add(visitKey)) {
            return Collections.emptyMap();
        }
        try {
            Map<String, String> resolved = new LinkedHashMap<String, String>();
            Map<String, String> directTargets = resolveInjectionTargetsByClassHierarchy(
                    arg.ownerClassName,
                    arg.token,
                    javaModel,
                    injectionRegistry
            );
            for (Map.Entry<String, String> direct : directTargets.entrySet()) {
                resolved.merge(direct.getKey(), direct.getValue(), (a, b) -> a.equals(b) ? a : a + "|" + b);
            }
            if (!resolved.isEmpty()) {
                return resolved;
            }
            if (isBlank(arg.ownerMethodId)) {
                return resolved;
            }

            MethodModel ownerMethod = methodsById.get(arg.ownerMethodId);
            if (ownerMethod == null || ownerMethod.parameterNames.isEmpty()) {
                return resolved;
            }
            int parameterIndex = ownerMethod.parameterNames.indexOf(arg.token);
            if (parameterIndex < 0) {
                return resolved;
            }

            List<CallerBinding> callerBindings = findCallerBindingsForMethod(
                    arg.ownerMethodId,
                    javaModel,
                    methodsById,
                    injectionRegistry,
                    callerBindingsCache
            );
            for (CallerBinding caller : callerBindings) {
                if (parameterIndex >= caller.argumentTokens.size()) {
                    continue;
                }
                String upstreamToken = normalizePipelineArgToken(caller.argumentTokens.get(parameterIndex));
                if (isBlank(upstreamToken)) {
                    continue;
                }
                PipelineAssemblyArg upstreamArg = new PipelineAssemblyArg(
                        upstreamToken,
                        caller.callerClassName,
                        caller.callerMethodId,
                        caller.line
                );
                Map<String, String> upstreamTargets = resolvePipelineAssemblyTargetsFromArg(
                        upstreamArg,
                        javaModel,
                        methodsById,
                        injectionRegistry,
                        callerBindingsCache,
                        visitingArgs,
                        depth + 1
                );
                for (Map.Entry<String, String> upstream : upstreamTargets.entrySet()) {
                    String reason = "PARAM_TRACE:" + arg.ownerMethodId + ":" + arg.token + "|" + upstream.getValue();
                    resolved.merge(upstream.getKey(), reason, (a, b) -> a.equals(b) ? a : a + "|" + b);
                }
            }
            return resolved;
        } finally {
            visitingArgs.remove(visitKey);
        }
    }

    private Map<String, String> resolveInjectionTargetsByClassHierarchy(String ownerClassName,
                                                                        String token,
                                                                        JavaModel javaModel,
                                                                        InjectionRegistry injectionRegistry) {
        if (isBlank(ownerClassName) || isBlank(token)) {
            return Collections.emptyMap();
        }
        Map<String, String> resolved = new LinkedHashMap<String, String>();
        Deque<String> stack = new ArrayDeque<String>();
        Set<String> visited = new LinkedHashSet<String>();
        stack.add(ownerClassName);
        while (!stack.isEmpty()) {
            String currentClass = stack.removeFirst();
            if (!visited.add(currentClass)) {
                continue;
            }
            Map<String, TargetSet> classTargets = injectionRegistry.targetsByClass.getOrDefault(
                    currentClass,
                    Collections.<String, TargetSet>emptyMap()
            );
            TargetSet targetSet = classTargets.get(token);
            if (targetSet != null && !targetSet.targets.isEmpty()) {
                for (String targetClass : targetSet.targets) {
                    resolved.merge(targetClass, targetSet.reasonSummary(), (a, b) -> a.equals(b) ? a : a + "|" + b);
                }
            }
            ClassModel currentClassModel = javaModel.classesByName.get(currentClass);
            if (currentClassModel == null) {
                continue;
            }
            if (!isBlank(currentClassModel.superType)) {
                stack.addAll(resolveDirectTypeNames(currentClassModel.superType, javaModel));
            }
        }
        return resolved;
    }

    private List<CallerBinding> findCallerBindingsForMethod(String targetMethodId,
                                                            JavaModel javaModel,
                                                            Map<String, MethodModel> methodsById,
                                                            InjectionRegistry injectionRegistry,
                                                            Map<String, List<CallerBinding>> cache) {
        List<CallerBinding> cached = cache.get(targetMethodId);
        if (cached != null) {
            return cached;
        }
        List<CallerBinding> resolved = new ArrayList<CallerBinding>();
        for (ClassModel callerClass : javaModel.classesByName.values()) {
            for (MethodModel callerMethod : callerClass.methodsById.values()) {
                for (CallSite call : callerMethod.calls) {
                    Map<String, String> callTargets = resolveCallCandidates(
                            callerClass,
                            callerMethod,
                            call,
                            javaModel,
                            methodsById,
                            injectionRegistry,
                            0
                    );
                    if (!callTargets.containsKey(targetMethodId)) {
                        continue;
                    }
                    resolved.add(new CallerBinding(
                            callerClass.fqcn,
                            callerMethod.id,
                            call.line,
                            call.argumentTokens
                    ));
                }
            }
        }
        cache.put(targetMethodId, resolved);
        return resolved;
    }

    private PipelineStepContext collectPipelineStepContext(Set<String> terminalMethodIds,
                                                           Map<String, MethodModel> methodsById,
                                                           JavaModel javaModel) {
        Set<PipelineStepCallSignature> signatures = new LinkedHashSet<PipelineStepCallSignature>();
        Set<String> receiverTypeNames = new LinkedHashSet<String>();
        for (String terminalMethodId : terminalMethodIds) {
            MethodModel terminalMethod = methodsById.get(terminalMethodId);
            if (terminalMethod == null) {
                continue;
            }
            ClassModel terminalClassModel = javaModel.classesByName.get(terminalMethod.className);
            for (CallSite terminalCall : terminalMethod.calls) {
                if (terminalCall.scopeType != ScopeType.NAME || isBlank(terminalCall.scopeToken)) {
                    continue;
                }
                String localType = terminalMethod.visibleVariableTypes.get(terminalCall.scopeToken);
                if (isBlank(localType)) {
                    continue;
                }
                String normalizedType = normalizeTypeName(localType);
                if (startsWithAnyIgnoreCase(normalizedType, "java.", "javax.", "jakarta.")) {
                    continue;
                }
                String normalizedMethod = terminalCall.methodName == null
                        ? ""
                        : terminalCall.methodName.toLowerCase(Locale.ROOT);
                if (PIPELINE_SKIP_STEP_METHOD_NAMES.contains(normalizedMethod)) {
                    continue;
                }
                signatures.add(new PipelineStepCallSignature(terminalCall.methodName, terminalCall.argumentCount));
                receiverTypeNames.add(normalizedType);
                receiverTypeNames.addAll(resolveClassesByTypeWithContext(localType, javaModel, terminalClassModel));
            }
        }
        return new PipelineStepContext(signatures, receiverTypeNames);
    }

    private String normalizePipelineArgToken(String raw) {
        if (isBlank(raw)) {
            return "";
        }
        String token = raw.trim();
        if (token.startsWith("this.")) {
            token = token.substring("this.".length()).trim();
        }
        if (token.startsWith("new ")) {
            return "";
        }
        return token;
    }

    private boolean isPipelineAssemblyCallSite(ClassModel classModel,
                                               MethodModel methodModel,
                                               CallSite callSite,
                                               JavaModel javaModel,
                                               Map<String, MethodModel> methodsById,
                                               InjectionRegistry injectionRegistry,
                                               Set<String> expectedStepTypes) {
        if (callSite == null) {
            return false;
        }
        String methodName = callSite.methodName == null ? "" : callSite.methodName.toLowerCase(Locale.ROOT);
        if (PIPELINE_ASSEMBLY_METHOD_NAMES.contains(methodName)) {
            return true;
        }
        if (callSite.argumentTokens.isEmpty()) {
            return false;
        }
        if (PIPELINE_TERMINAL_METHOD_NAMES.contains(methodName)
                || PIPELINE_SKIP_STEP_METHOD_NAMES.contains(methodName)
                || "builder".equals(methodName)
                || "build".equals(methodName)) {
            return false;
        }

        boolean hasMeaningfulArg = false;
        for (String token : callSite.argumentTokens) {
            String normalized = normalizePipelineArgToken(token);
            if (!isBlank(normalized) && !isLikelyLiteralToken(normalized)) {
                hasMeaningfulArg = true;
                break;
            }
        }
        if (!hasMeaningfulArg) {
            return false;
        }

        Map<String, String> targets = resolveCallCandidates(
                classModel,
                methodModel,
                callSite,
                javaModel,
                methodsById,
                injectionRegistry,
                0
        );
        for (String targetMethodId : targets.keySet()) {
            MethodModel targetMethod = methodsById.get(targetMethodId);
            if (targetMethod == null) {
                continue;
            }
            ClassModel targetClassModel = javaModel.classesByName.get(targetMethod.className);
            if (hasCompatibleStageParameter(targetMethod, targetClassModel, expectedStepTypes, javaModel)) {
                return true;
            }
            if (isLikelyFluentAssemblyTarget(targetMethod, javaModel)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCompatibleStageParameter(MethodModel targetMethod,
                                                ClassModel targetClassModel,
                                                Set<String> expectedStepTypes,
                                                JavaModel javaModel) {
        if (targetMethod == null || targetMethod.parameterNames.isEmpty() || expectedStepTypes == null || expectedStepTypes.isEmpty()) {
            return false;
        }
        for (String parameterName : targetMethod.parameterNames) {
            String parameterType = targetMethod.visibleVariableTypes.get(parameterName);
            if (isBlank(parameterType)) {
                continue;
            }
            if (isTypeCompatibleWithExpectedSteps(parameterType, expectedStepTypes, javaModel, targetClassModel)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTypeCompatibleWithExpectedSteps(String parameterType,
                                                      Set<String> expectedStepTypes,
                                                      JavaModel javaModel,
                                                      ClassModel contextClass) {
        String normalizedParamType = normalizeTypeName(parameterType);
        if (isBlank(normalizedParamType) || expectedStepTypes.isEmpty()) {
            return false;
        }
        Set<String> paramCandidates = new LinkedHashSet<String>(resolveClassesByTypeWithContext(normalizedParamType, javaModel, contextClass));
        if (paramCandidates.isEmpty()) {
            paramCandidates.add(normalizedParamType);
        }

        Set<String> expectedCandidates = new LinkedHashSet<String>();
        for (String expected : expectedStepTypes) {
            if (isBlank(expected)) {
                continue;
            }
            String normalizedExpected = normalizeTypeName(expected);
            if (isBlank(normalizedExpected)) {
                continue;
            }
            expectedCandidates.add(normalizedExpected);
            expectedCandidates.addAll(resolveClassesByTypeWithContext(normalizedExpected, javaModel, contextClass));
        }

        for (String paramTypeCandidate : paramCandidates) {
            if (isBlank(paramTypeCandidate)) {
                continue;
            }
            for (String expectedCandidate : expectedCandidates) {
                if (isBlank(expectedCandidate)) {
                    continue;
                }
                if (simpleName(paramTypeCandidate).equals(simpleName(expectedCandidate))) {
                    return true;
                }
                if (isAssignableTo(paramTypeCandidate, expectedCandidate, javaModel, new HashSet<String>())
                        || isAssignableTo(expectedCandidate, paramTypeCandidate, javaModel, new HashSet<String>())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isLikelyFluentAssemblyTarget(MethodModel targetMethod, JavaModel javaModel) {
        if (targetMethod == null || targetMethod.parameterCount <= 0) {
            return false;
        }
        String returnType = normalizeTypeName(targetMethod.returnTypeName);
        if (isBlank(returnType) || "void".equals(returnType)) {
            return false;
        }
        if (targetMethod.className.equals(returnType) || simpleName(targetMethod.className).equals(simpleName(returnType))) {
            return true;
        }
        ClassModel targetClassModel = javaModel.classesByName.get(targetMethod.className);
        Set<String> returnTypeCandidates = resolveClassesByTypeWithContext(returnType, javaModel, targetClassModel);
        for (String candidate : returnTypeCandidates) {
            if (isBlank(candidate)) {
                continue;
            }
            if (targetMethod.className.equals(candidate)) {
                return true;
            }
            if (isAssignableTo(targetMethod.className, candidate, javaModel, new HashSet<String>())
                    || isAssignableTo(candidate, targetMethod.className, javaModel, new HashSet<String>())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLikelyLiteralToken(String token) {
        if (isBlank(token)) {
            return true;
        }
        String normalized = token.trim();
        if ("null".equalsIgnoreCase(normalized)
                || "true".equalsIgnoreCase(normalized)
                || "false".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ((normalized.startsWith("\"") && normalized.endsWith("\""))
                || (normalized.startsWith("'") && normalized.endsWith("'"))) {
            return true;
        }
        return normalized.matches("-?\\d+(\\.\\d+)?[dDfFlL]?");
    }

    private static boolean isLikelyTypeReferenceToken(String token) {
        if (isBlank(token)) {
            return false;
        }
        char first = token.trim().charAt(0);
        return Character.isUpperCase(first);
    }

    private Map<String, String> resolveCallCandidates(ClassModel classModel,
                                                      MethodModel methodModel,
                                                      CallSite call,
                                                      JavaModel javaModel,
                                                      Map<String, MethodModel> methodsById,
                                                      InjectionRegistry injectionRegistry,
                                                      int depth) {
        if (depth > CALL_RESOLVE_MAX_DEPTH) {
            debugLogger.log("RESOLVE_DEPTH_GUARD from=%s call=%s/%d", methodModel.id, call.methodName, call.argumentCount);
            return Collections.emptyMap();
        }

        Map<String, String> resolved = new LinkedHashMap<>();
        if (call.scopeType == ScopeType.UNSCOPED || call.scopeType == ScopeType.THIS) {
            addResolvedTargets(resolved, resolveMethodInClassAndParents(classModel, call, javaModel), "JAVA:this");
            addResolvedTargets(resolved, resolveMethodInDescendants(classModel, call, javaModel), "JAVA:this-override");
            return resolved;
        }

        if (call.scopeType == ScopeType.NAME && call.scopeToken != null) {
            Map<String, TargetSet> classTargets = injectionRegistry.targetsByClass.getOrDefault(classModel.fqcn, Collections.emptyMap());
            TargetSet targetSet = classTargets.get(call.scopeToken);
            if (targetSet != null && !targetSet.targets.isEmpty()) {
                for (String targetClass : targetSet.targets) {
                    addResolvedTargets(
                            resolved,
                            resolveMethodInClassHierarchy(targetClass, call, javaModel),
                            targetSet.reasonSummary()
                    );
                }
            } else {
                String localType = methodModel.visibleVariableTypes.get(call.scopeToken);
                if (localType != null) {
                    Set<String> localCandidates = resolveClassesByTypeWithContext(localType, javaModel, classModel);
                    for (String localCandidate : localCandidates) {
                        addResolvedTargets(resolved, resolveMethodInClassHierarchy(localCandidate, call, javaModel), "JAVA:local-var");
                    }
                } else {
                    FieldModel fieldModel = classModel.fields.get(call.scopeToken);
                    if (fieldModel != null) {
                        Set<String> typeCandidates = resolveClassesByTypeWithContext(fieldModel.typeName, javaModel, classModel);
                        for (String typeCandidate : typeCandidates) {
                            addResolvedTargets(resolved, resolveMethodInClassHierarchy(typeCandidate, call, javaModel), "JAVA:field-type");
                        }
                    } else if (isLikelyTypeReferenceToken(call.scopeToken)) {
                        Set<String> staticTypeCandidates = resolveClassesByTypeWithContext(call.scopeToken, javaModel, classModel);
                        for (String staticTypeCandidate : staticTypeCandidates) {
                            addResolvedTargets(
                                    resolved,
                                    resolveMethodInClassHierarchy(staticTypeCandidate, call, javaModel),
                                    "JAVA:static-type"
                            );
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
                    debugLogger.log("RESOLVE_CHAIN_SKIP from=%s scopeMethod=%s", methodModel.id, scopeCall.getKey());
                    continue;
                }
                ClassModel scopeClassModel = javaModel.classesByName.get(scopeMethod.className);
                Set<String> receiverTypes = new LinkedHashSet<String>(
                        resolveClassesByTypeWithContext(scopeMethod.returnTypeName, javaModel, scopeClassModel)
                );
                boolean likelyTypeVariable = isLikelyTypeVariable(scopeMethod.returnTypeName);
                if (likelyTypeVariable) {
                    receiverTypes.clear();
                }
                boolean genericFallback = receiverTypes.isEmpty() || likelyTypeVariable;
                if (genericFallback) {
                    receiverTypes.addAll(resolveClassesByType(scopeMethod.className, javaModel));
                    debugLogger.log(
                            "RESOLVE_CHAIN_FALLBACK from=%s scopeMethod=%s returnType=%s receivers=%s",
                            methodModel.id,
                            scopeCall.getKey(),
                            scopeMethod.returnTypeName,
                            receiverTypes
                    );
                }
                for (String scopeType : receiverTypes) {
                    addResolvedTargets(
                            resolved,
                            resolveMethodInClassHierarchy(scopeType, call, javaModel),
                            "CHAIN:" + scopeCall.getValue()
                    );
                }
            }
        }
        if (resolved.isEmpty()) {
            debugLogger.log(
                    "RESOLVE_NO_TARGET from=%s call=%s/%d scope=%s scopeToken=%s",
                    methodModel.id,
                    call.methodName,
                    call.argumentCount,
                    call.scopeType,
                    call.scopeToken
            );
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
        return resolveMethodInClassHierarchy(classModel.fqcn, call, javaModel);
    }

    private Set<String> resolveMethodInDescendants(ClassModel classModel, CallSite call, JavaModel javaModel) {
        if (classModel == null) {
            return setOf();
        }
        Set<String> resolved = new LinkedHashSet<String>();
        for (ClassModel candidate : javaModel.classesByName.values()) {
            if (candidate.fqcn.equals(classModel.fqcn)) {
                continue;
            }
            if (!isAssignableTo(candidate.fqcn, classModel.fqcn, javaModel, new HashSet<String>())) {
                continue;
            }
            resolved.addAll(resolveMethodByName(candidate.fqcn, call, javaModel));
        }
        return resolved;
    }

    private Set<String> resolveMethodByReceiverDispatch(String className, CallSite call, JavaModel javaModel) {
        if (isBlank(className)) {
            return setOf();
        }
        Set<String> visited = new LinkedHashSet<String>();
        Deque<String> queue = new ArrayDeque<String>();
        queue.add(className);
        while (!queue.isEmpty()) {
            String currentClass = queue.removeFirst();
            if (!visited.add(currentClass)) {
                continue;
            }
            Set<String> methods = resolveMethodByName(currentClass, call, javaModel);
            if (!methods.isEmpty()) {
                return methods;
            }
            ClassModel currentModel = javaModel.classesByName.get(currentClass);
            if (currentModel == null) {
                continue;
            }
            if (!isBlank(currentModel.superType)) {
                queue.addAll(resolveDirectTypeNames(currentModel.superType, javaModel));
            }
            for (String iface : currentModel.implementedTypes) {
                queue.addAll(resolveDirectTypeNames(iface, javaModel));
            }
        }
        return setOf();
    }

    private Set<String> resolveMethodInClassHierarchy(String className, CallSite call, JavaModel javaModel) {
        Set<String> resolved = new LinkedHashSet<String>();
        Deque<String> stack = new ArrayDeque<String>();
        Set<String> visited = new HashSet<String>();
        stack.add(className);

        while (!stack.isEmpty()) {
            String currentClass = stack.removeFirst();
            if (!visited.add(currentClass)) {
                continue;
            }
            resolved.addAll(resolveMethodByName(currentClass, call, javaModel));

            ClassModel currentModel = javaModel.classesByName.get(currentClass);
            if (currentModel == null) {
                continue;
            }
            if (!isBlank(currentModel.superType)) {
                stack.addAll(resolveDirectTypeNames(currentModel.superType, javaModel));
            }
            for (String iface : currentModel.implementedTypes) {
                stack.addAll(resolveDirectTypeNames(iface, javaModel));
            }
        }

        return resolved;
    }

    private Set<String> resolveMethodByName(String className, CallSite call, JavaModel javaModel) {
        ClassModel target = javaModel.classesByName.get(className);
        if (target == null) {
            if (isBlank(className)) {
                return setOf();
            }
            return setOf(methodId(className, call.methodName, call.argumentCount));
        }
        List<MethodModel> methods = target.methodsByName.getOrDefault(call.methodName, listOf());
        Set<String> exact = methods.stream()
                .filter(m -> m.parameterCount == call.argumentCount)
                .map(m -> m.id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!exact.isEmpty()) {
            return exact;
        }
        if (methods.size() == 1) {
            return setOf(methods.get(0).id);
        }
        return setOf();
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

    private Set<String> resolveClassesByTypeWithContext(String typeName, JavaModel javaModel, ClassModel contextClass) {
        Set<String> candidates = new LinkedHashSet<String>(resolveClassesByType(typeName, javaModel));
        if (!candidates.isEmpty()) {
            return candidates;
        }

        String normalized = normalizeTypeName(typeName);
        if (isBlank(normalized)) {
            return candidates;
        }
        if (normalized.contains(".")) {
            candidates.add(normalized);
            return candidates;
        }

        if (contextClass != null) {
            String imported = contextClass.importContext.directImports.get(normalized);
            if (!isBlank(imported)) {
                candidates.add(imported);
            }
            for (String wildcard : contextClass.importContext.wildcardImports) {
                if (!isBlank(wildcard)) {
                    candidates.add(wildcard + "." + normalized);
                }
            }
            if (!isBlank(contextClass.packageName)) {
                String samePackageClass = contextClass.packageName + "." + normalized;
                if (javaModel.classesByName.containsKey(samePackageClass)) {
                    candidates.add(samePackageClass);
                }
            }
        }

        if (JAVA_LANG_COMMON_TYPES.contains(normalized)) {
            candidates.add("java.lang." + normalized);
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

    private static boolean startsWithAnyIgnoreCase(String raw, String... prefixes) {
        if (raw == null) {
            return false;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        for (String prefix : prefixes) {
            if (lower.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLikelyTypeVariable(String typeName) {
        String normalized = normalizeTypeName(typeName);
        if (isBlank(normalized)) {
            return false;
        }
        if (normalized.contains(".") || normalized.contains("$")) {
            return false;
        }
        if (normalized.length() == 1 && Character.isUpperCase(normalized.charAt(0))) {
            return true;
        }
        return normalized.matches("[A-Z][A-Z0-9_]*");
    }

    private static String simpleName(String fqcnOrSimple) {
        if (fqcnOrSimple == null) {
            return "";
        }
        int idx = fqcnOrSimple.lastIndexOf('.');
        return idx >= 0 ? fqcnOrSimple.substring(idx + 1) : fqcnOrSimple;
    }

    private static String normalizeXmlTagName(String tagName) {
        if (tagName == null) {
            return "";
        }
        int idx = tagName.indexOf(':');
        if (idx >= 0 && idx + 1 < tagName.length()) {
            return tagName.substring(idx + 1);
        }
        return tagName;
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

    private static Set<String> normalizePackagePrefixes(Collection<String> rawPrefixes) {
        if (rawPrefixes == null || rawPrefixes.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> normalized = new LinkedHashSet<String>();
        for (String rawPrefix : rawPrefixes) {
            if (isBlank(rawPrefix)) {
                continue;
            }
            String value = rawPrefix.trim().toLowerCase(Locale.ROOT);
            if (value.endsWith(".")) {
                value = value.substring(0, value.length() - 1);
            }
            if (!isBlank(value)) {
                normalized.add(value);
            }
        }
        if (normalized.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(normalized);
    }

    private static void collectPackagePrefixes(Set<String> out, String raw) {
        if (out == null || isBlank(raw)) {
            return;
        }
        String prepared = raw.trim();
        if ((prepared.startsWith("[") && prepared.endsWith("]"))
                || (prepared.startsWith("(") && prepared.endsWith(")"))) {
            prepared = prepared.substring(1, prepared.length() - 1).trim();
        }

        String[] tokens = prepared.split("[,;]");
        if (tokens.length == 1 && prepared.contains(" ")) {
            tokens = prepared.split("\\s+");
        }

        for (String token : tokens) {
            String trimmed = token == null ? "" : token.trim();
            if (trimmed.length() >= 2
                    && ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                    || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
                trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
            }
            if (!isBlank(trimmed)) {
                out.add(trimmed);
            }
        }
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
        public final String endpointPath;
        public final String entryMethod;
        public final boolean debug;
        public final Path debugOut;
        public final Set<String> externalRpcPrefixes;
        public final Set<String> nonExternalRpcPrefixes;

        private CliOptions(Path projectRoot,
                           Path outputDir,
                           int maxDepth,
                           int maxPathsPerEndpoint,
                           String endpointPath,
                           String entryMethod,
                           boolean debug,
                           Path debugOut,
                           Set<String> externalRpcPrefixes,
                           Set<String> nonExternalRpcPrefixes) {
            this.projectRoot = projectRoot;
            this.outputDir = outputDir;
            this.maxDepth = maxDepth;
            this.maxPathsPerEndpoint = maxPathsPerEndpoint;
            this.endpointPath = endpointPath;
            this.entryMethod = entryMethod;
            this.debug = debug;
            this.debugOut = debugOut;
            this.externalRpcPrefixes = externalRpcPrefixes;
            this.nonExternalRpcPrefixes = nonExternalRpcPrefixes;
        }

        public static CliOptions parse(String[] args) {
            Path projectRoot = Paths.get(".").toAbsolutePath().normalize();
            Path outputDir = projectRoot.resolve("build/reports/spring-call-path");
            int maxDepth = 50;
            int maxPaths = 200;
            String endpointPath = null;
            String entryMethod = null;
            boolean debug = false;
            Path debugOut = null;
            Set<String> externalRpcPrefixes = new LinkedHashSet<String>();
            Set<String> nonExternalRpcPrefixes = new LinkedHashSet<String>();

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
                } else if ("--endpoint".equals(arg) && i + 1 < args.length) {
                    endpointPath = args[++i];
                } else if ("--entry-method".equals(arg) && i + 1 < args.length) {
                    entryMethod = args[++i];
                } else if ("--debug".equals(arg)) {
                    debug = true;
                } else if ("--debug-out".equals(arg) && i + 1 < args.length) {
                    debugOut = Paths.get(args[++i]).toAbsolutePath().normalize();
                } else if ("--external-rpc-prefix".equals(arg) && i + 1 < args.length) {
                    collectPackagePrefixes(externalRpcPrefixes, args[++i]);
                } else if ("--non-external-rpc-prefix".equals(arg) && i + 1 < args.length) {
                    collectPackagePrefixes(nonExternalRpcPrefixes, args[++i]);
                } else if ("--internal-jar-prefix".equals(arg) && i + 1 < args.length) {
                    // Backward compatible alias of --non-external-rpc-prefix
                    collectPackagePrefixes(nonExternalRpcPrefixes, args[++i]);
                }
            }

            return new CliOptions(
                    projectRoot,
                    outputDir,
                    maxDepth,
                    maxPaths,
                    endpointPath,
                    entryMethod,
                    debug,
                    debugOut,
                    normalizePackagePrefixes(externalRpcPrefixes),
                    normalizePackagePrefixes(nonExternalRpcPrefixes)
            );
        }
    }

    public static class MethodSelector {
        public final String className;
        public final String methodName;
        public final Integer parameterCount;

        public MethodSelector(String className, String methodName, Integer parameterCount) {
            this.className = className;
            this.methodName = methodName;
            this.parameterCount = parameterCount;
        }

        public static MethodSelector parse(String raw) {
            String value = raw == null ? "" : raw.trim();
            String className = null;
            String methodPart = value;
            Integer parameterCount = null;

            int hashIndex = value.lastIndexOf('#');
            if (hashIndex >= 0) {
                className = value.substring(0, hashIndex).trim();
                methodPart = value.substring(hashIndex + 1).trim();
            }

            int slashIndex = methodPart.lastIndexOf('/');
            if (slashIndex > 0 && slashIndex + 1 < methodPart.length()) {
                String arity = methodPart.substring(slashIndex + 1).trim();
                try {
                    parameterCount = Integer.parseInt(arity);
                    methodPart = methodPart.substring(0, slashIndex).trim();
                } catch (NumberFormatException ignored) {
                    parameterCount = null;
                }
            }

            int parenIndex = methodPart.indexOf('(');
            if (parenIndex > 0) {
                methodPart = methodPart.substring(0, parenIndex).trim();
            }

            return new MethodSelector(className, methodPart, parameterCount);
        }

        public boolean matches(MethodModel method) {
            if (!isBlank(className)) {
                String methodClassName = method.className;
                if (!className.equals(methodClassName) && !className.equals(simpleName(methodClassName))) {
                    return false;
                }
            }
            if (!isBlank(methodName) && !methodName.equals(method.name)) {
                return false;
            }
            if (parameterCount != null && parameterCount.intValue() != method.parameterCount) {
                return false;
            }
            return true;
        }
    }

    public static class MethodIdInfo {
        public final String className;
        public final String methodName;
        public final int parameterCount;

        public MethodIdInfo(String className, String methodName, int parameterCount) {
            this.className = className;
            this.methodName = methodName;
            this.parameterCount = parameterCount;
        }

        public static MethodIdInfo parse(String methodId) {
            if (isBlank(methodId)) {
                return null;
            }
            int hashIndex = methodId.lastIndexOf('#');
            int slashIndex = methodId.lastIndexOf('/');
            if (hashIndex <= 0 || slashIndex <= hashIndex) {
                return null;
            }
            String className = methodId.substring(0, hashIndex);
            String methodName = methodId.substring(hashIndex + 1, slashIndex);
            String arityRaw = methodId.substring(slashIndex + 1);
            try {
                int arity = Integer.parseInt(arityRaw);
                return new MethodIdInfo(className, methodName, arity);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    public static class CodeAnalysisResult {
        public final String projectRoot;
        public final JavaModel javaModel;
        public final Map<String, MethodModel> methodsById;
        public final Map<String, String> methodDisplay;
        public final List<CallEdge> edges;
        public final List<EndpointPaths> endpoints;

        public CodeAnalysisResult(String projectRoot,
                                  JavaModel javaModel,
                                  Map<String, MethodModel> methodsById,
                                  Map<String, String> methodDisplay,
                                  List<CallEdge> edges,
                                  List<EndpointPaths> endpoints) {
            this.projectRoot = projectRoot;
            this.javaModel = javaModel;
            this.methodsById = methodsById;
            this.methodDisplay = methodDisplay;
            this.edges = edges;
            this.endpoints = endpoints;
        }
    }

    public static class AnalysisReport {
        public final String generatedAt;
        public final String projectRoot;
        public final Map<String, String> methodDisplay;
        public final List<CallEdge> edges;
        public final List<EndpointPaths> endpoints;
        public final List<ExternalDependencyTree> externalDependencyTrees;

        public AnalysisReport(String generatedAt,
                              String projectRoot,
                              Map<String, String> methodDisplay,
                              List<CallEdge> edges,
                              List<EndpointPaths> endpoints,
                              List<ExternalDependencyTree> externalDependencyTrees) {
            this.generatedAt = generatedAt;
            this.projectRoot = projectRoot;
            this.methodDisplay = methodDisplay;
            this.edges = edges;
            this.endpoints = endpoints;
            this.externalDependencyTrees = externalDependencyTrees;
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

        public String toDependencyTreeText() {
            if (externalDependencyTrees == null || externalDependencyTrees.isEmpty()) {
                return "No external dependencies detected.\n";
            }

            StringBuilder sb = new StringBuilder();
            for (ExternalDependencyTree tree : externalDependencyTrees) {
                sb.append("Entry: ")
                        .append(tree.path)
                        .append(" [")
                        .append(String.join(",", tree.httpMethods))
                        .append("] source=")
                        .append(tree.source)
                        .append("\n");
                appendDependencyNode(sb, tree.root, "  ");
                sb.append("\n");
            }
            return sb.toString();
        }

        private void appendDependencyNode(StringBuilder sb, DependencyNode node, String indent) {
            if (node == null) {
                return;
            }
            String display = node.methodDisplay == null ? node.methodId : node.methodDisplay;
            sb.append(indent).append(display).append("\n");
            for (DependencyEdge edge : node.children) {
                sb.append(indent).append("  -> ").append(edge.toMethodDisplay == null ? edge.toMethodId : edge.toMethodDisplay);
                if (!edge.externalDependencyTypes.isEmpty()) {
                    sb.append(" [").append(String.join(",", edge.externalDependencyTypes)).append("]");
                }
                if (!isBlank(edge.callReason)) {
                    sb.append(" {").append(edge.callReason).append("}");
                }
                sb.append("\n");
                appendDependencyNode(sb, edge.child, indent + "    ");
            }
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

    public static class ExternalDependencyTree {
        public final String path;
        public final List<String> httpMethods;
        public final String source;
        public final String entryMethodId;
        public final DependencyNode root;

        public ExternalDependencyTree(String path,
                                      List<String> httpMethods,
                                      String source,
                                      String entryMethodId,
                                      DependencyNode root) {
            this.path = path;
            this.httpMethods = httpMethods;
            this.source = source;
            this.entryMethodId = entryMethodId;
            this.root = root;
        }
    }

    public static class DependencyNode {
        public final String methodId;
        public final String methodDisplay;
        public final List<DependencyEdge> children;

        public DependencyNode(String methodId, String methodDisplay, List<DependencyEdge> children) {
            this.methodId = methodId;
            this.methodDisplay = methodDisplay;
            this.children = children;
        }
    }

    public static class DependencyEdge {
        public final String fromMethodId;
        public final String toMethodId;
        public final String toMethodDisplay;
        public final List<String> externalDependencyTypes;
        public final String callReason;
        public final int line;
        public final DependencyNode child;

        public DependencyEdge(String fromMethodId,
                              String toMethodId,
                              String toMethodDisplay,
                              List<String> externalDependencyTypes,
                              String callReason,
                              int line,
                              DependencyNode child) {
            this.fromMethodId = fromMethodId;
            this.toMethodId = toMethodId;
            this.toMethodDisplay = toMethodDisplay;
            this.externalDependencyTypes = externalDependencyTypes;
            this.callReason = callReason;
            this.line = line;
            this.child = child;
        }
    }

    public static class CallEdge {
        public final String from;
        public final String to;
        public final String reason;
        public final int line;
        public final List<String> externalDependencyTypes;

        public CallEdge(String from, String to, String reason, int line, List<String> externalDependencyTypes) {
            this.from = from;
            this.to = to;
            this.reason = reason;
            this.line = line;
            this.externalDependencyTypes = externalDependencyTypes == null ? listOf() : externalDependencyTypes;
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
        public final boolean isInterface;
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

        public ClassModel(String fqcn,
                          String simpleName,
                          String packageName,
                          ImportContext importContext,
                          boolean isInterface) {
            this.fqcn = fqcn;
            this.simpleName = simpleName;
            this.packageName = packageName;
            this.importContext = importContext;
            this.isInterface = isInterface;
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
        public final List<String> parameterNames = new ArrayList<String>();
        public final List<CallSite> calls = new ArrayList<>();
        public final Map<String, String> visibleVariableTypes = new LinkedHashMap<>();
        public final List<AssignmentFlow> assignments = new ArrayList<AssignmentFlow>();
        public final List<ReturnFlow> returns = new ArrayList<ReturnFlow>();

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

    public static class ValueExpr {
        public final String variableName;
        public final CallSite callSite;

        private ValueExpr(String variableName, CallSite callSite) {
            this.variableName = variableName;
            this.callSite = callSite;
        }

        public static ValueExpr forVariable(String variableName) {
            return new ValueExpr(variableName, null);
        }

        public static ValueExpr forCall(CallSite callSite) {
            return new ValueExpr(null, callSite);
        }

        public boolean isVariable() {
            return !isBlank(variableName);
        }

        public boolean isCall() {
            return callSite != null;
        }
    }

    public static class AssignmentFlow {
        public final String targetVar;
        public final ValueExpr value;
        public final int line;

        public AssignmentFlow(String targetVar, ValueExpr value, int line) {
            this.targetVar = targetVar;
            this.value = value;
            this.line = line;
        }
    }

    public static class ReturnFlow {
        public final ValueExpr value;
        public final int line;

        public ReturnFlow(ValueExpr value, int line) {
            this.value = value;
            this.line = line;
        }
    }

    public static class CallSite {
        public final ScopeType scopeType;
        public final String scopeToken;
        public final CallSite scopeCall;
        public final String methodName;
        public final int argumentCount;
        public final int line;
        public final List<String> argumentTokens;

        public CallSite(ScopeType scopeType,
                        String scopeToken,
                        CallSite scopeCall,
                        String methodName,
                        int argumentCount,
                        int line,
                        List<String> argumentTokens) {
            this.scopeType = scopeType;
            this.scopeToken = scopeToken;
            this.scopeCall = scopeCall;
            this.methodName = methodName;
            this.argumentCount = argumentCount;
            this.line = line;
            this.argumentTokens = argumentTokens == null ? new ArrayList<String>() : argumentTokens;
        }
    }

    private static class TypeFlowContext {
        public final Set<String> thisTypeNames = new LinkedHashSet<String>();
        public final Map<String, Set<String>> variableTypes = new LinkedHashMap<String, Set<String>>();

        private static TypeFlowContext forRoot(String thisClassName) {
            TypeFlowContext context = new TypeFlowContext();
            if (!isBlank(thisClassName)) {
                context.thisTypeNames.add(thisClassName);
            }
            return context;
        }

        private String signature() {
            List<String> parts = new ArrayList<String>();
            List<String> thisParts = new ArrayList<String>(thisTypeNames);
            Collections.sort(thisParts);
            parts.add("this=" + String.join(",", thisParts));
            List<String> varKeys = new ArrayList<String>(variableTypes.keySet());
            Collections.sort(varKeys);
            for (String key : varKeys) {
                Set<String> value = variableTypes.get(key);
                List<String> sortedTypes = value == null ? new ArrayList<String>() : new ArrayList<String>(value);
                Collections.sort(sortedTypes);
                parts.add(key + "=" + String.join(",", sortedTypes));
            }
            return String.join(";", parts);
        }
    }

    private static class ResolvedCallTarget {
        public final String methodId;
        public final String reason;
        public final String receiverClassName;

        private ResolvedCallTarget(String methodId, String reason, String receiverClassName) {
            this.methodId = methodId;
            this.reason = isBlank(reason) ? "JAVA:unknown" : reason;
            this.receiverClassName = receiverClassName == null ? "" : receiverClassName;
        }
    }

    private static class PipelineAssemblyArg {
        public final String token;
        public final String ownerClassName;
        public final String ownerMethodId;
        public final int line;

        private PipelineAssemblyArg(String token, String ownerClassName, String ownerMethodId, int line) {
            this.token = token;
            this.ownerClassName = ownerClassName;
            this.ownerMethodId = ownerMethodId;
            this.line = line;
        }
    }

    private static class CallerBinding {
        public final String callerClassName;
        public final String callerMethodId;
        public final int line;
        public final List<String> argumentTokens;

        private CallerBinding(String callerClassName, String callerMethodId, int line, List<String> argumentTokens) {
            this.callerClassName = callerClassName;
            this.callerMethodId = callerMethodId;
            this.line = line;
            this.argumentTokens = argumentTokens == null ? new ArrayList<String>() : new ArrayList<String>(argumentTokens);
        }
    }

    private static class PipelineStepCallSignature {
        public final String methodName;
        public final int argumentCount;

        private PipelineStepCallSignature(String methodName, int argumentCount) {
            this.methodName = methodName;
            this.argumentCount = argumentCount;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof PipelineStepCallSignature)) {
                return false;
            }
            PipelineStepCallSignature other = (PipelineStepCallSignature) obj;
            if (argumentCount != other.argumentCount) {
                return false;
            }
            if (methodName == null) {
                return other.methodName == null;
            }
            return methodName.equals(other.methodName);
        }

        @Override
        public int hashCode() {
            return (methodName == null ? 0 : methodName.hashCode()) * 31 + argumentCount;
        }
    }

    private static class PipelineStepContext {
        public final Set<PipelineStepCallSignature> signatures;
        public final Set<String> receiverTypeNames;

        private PipelineStepContext(Set<PipelineStepCallSignature> signatures, Set<String> receiverTypeNames) {
            this.signatures = signatures == null
                    ? Collections.<PipelineStepCallSignature>emptySet()
                    : new LinkedHashSet<PipelineStepCallSignature>(signatures);
            this.receiverTypeNames = receiverTypeNames == null
                    ? Collections.<String>emptySet()
                    : new LinkedHashSet<String>(receiverTypeNames);
        }
    }

    public static class CallEdgeAccumulator {
        public final String from;
        public final String to;
        public final Set<String> reasons = new LinkedHashSet<>();
        public final int line;
        public int occurrences = 1;

        public CallEdgeAccumulator(String from, String to, int line) {
            this.from = from;
            this.to = to;
            this.line = line;
        }
    }

    private static class MutableDependencyNode {
        private final String methodId;
        private final String methodDisplay;
        private final Map<String, MutableDependencyEdge> childrenByTarget = new LinkedHashMap<String, MutableDependencyEdge>();

        private MutableDependencyNode(String methodId, String methodDisplay) {
            this.methodId = methodId;
            this.methodDisplay = methodDisplay;
        }

        private MutableDependencyNode addChild(String fromMethodId,
                                               String toMethodId,
                                               String toMethodDisplay,
                                               List<String> dependencyTypes,
                                               String callReason,
                                               int line) {
            MutableDependencyEdge edge = childrenByTarget.get(toMethodId);
            if (edge == null) {
                edge = new MutableDependencyEdge(
                        fromMethodId,
                        toMethodId,
                        toMethodDisplay,
                        callReason,
                        line
                );
                childrenByTarget.put(toMethodId, edge);
            }
            if (dependencyTypes != null) {
                edge.externalDependencyTypes.addAll(dependencyTypes);
            }
            return edge.child;
        }

        private DependencyNode toImmutable() {
            List<DependencyEdge> children = new ArrayList<DependencyEdge>();
            for (MutableDependencyEdge edge : childrenByTarget.values()) {
                children.add(new DependencyEdge(
                        edge.fromMethodId,
                        edge.toMethodId,
                        edge.toMethodDisplay,
                        new ArrayList<String>(edge.externalDependencyTypes),
                        edge.callReason,
                        edge.line,
                        edge.child.toImmutable()
                ));
            }
            return new DependencyNode(methodId, methodDisplay, children);
        }
    }

    private static class MutableDependencyEdge {
        private final String fromMethodId;
        private final String toMethodId;
        private final String toMethodDisplay;
        private final Set<String> externalDependencyTypes = new LinkedHashSet<String>();
        private final String callReason;
        private final int line;
        private final MutableDependencyNode child;

        private MutableDependencyEdge(String fromMethodId,
                                      String toMethodId,
                                      String toMethodDisplay,
                                      String callReason,
                                      int line) {
            this.fromMethodId = fromMethodId;
            this.toMethodId = toMethodId;
            this.toMethodDisplay = toMethodDisplay;
            this.callReason = callReason;
            this.line = line;
            this.child = new MutableDependencyNode(toMethodId, toMethodDisplay);
        }
    }

    public static class DebugLogger {
        private final boolean enabled;
        private final Path outPath;
        private final List<String> lines = new ArrayList<String>();

        public DebugLogger(boolean enabled, Path outPath) {
            this.enabled = enabled;
            this.outPath = outPath;
        }

        public static DebugLogger disabled() {
            return new DebugLogger(false, null);
        }

        public boolean isEnabled() {
            return enabled;
        }

        public Path getOutPath() {
            return outPath;
        }

        public void log(String pattern, Object... args) {
            if (!enabled) {
                return;
            }
            String message;
            if (args == null || args.length == 0) {
                message = pattern;
            } else {
                message = String.format(Locale.ROOT, pattern, args);
            }
            lines.add(Instant.now().toString() + " " + message);
        }

        public void flush() throws IOException {
            if (!enabled || outPath == null) {
                return;
            }
            Path parent = outPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(outPath, lines, StandardCharsets.UTF_8);
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
