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
    private DebugLogger debugLogger = DebugLogger.disabled();
    private Set<String> externalRpcPrefixes = Collections.emptySet();
    private Set<String> nonExternalRpcPrefixes = Collections.emptySet();
    private static final Set<String> COMPONENT_ANNOTATIONS = setOf(
            "Component", "Service", "Repository", "Controller", "RestController", "Configuration"
    );
    private static final Set<String> INJECTION_ANNOTATIONS = setOf("Autowired", "Inject", "Resource");
    private static final Set<String> REQUEST_MAPPING_ANNOTATIONS = setOf(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping"
    );

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
        DebugLogger previousLogger = this.debugLogger;
        Set<String> previousExternalRpcPrefixes = this.externalRpcPrefixes;
        Set<String> previousNonExternalRpcPrefixes = this.nonExternalRpcPrefixes;
        this.debugLogger = debugLogger == null ? DebugLogger.disabled() : debugLogger;
        this.externalRpcPrefixes = normalizePackagePrefixes(externalRpcPrefixes);
        this.nonExternalRpcPrefixes = normalizePackagePrefixes(nonExternalRpcPrefixes);
        try {
        JavaModel javaModel = parseJava(projectRoot);
        XmlModel xmlModel = parseXml(projectRoot);
        this.debugLogger.log(
                "START project=%s endpointFilter=%s entryMethodFilter=%s maxDepth=%d maxPaths=%d externalRpcPrefixes=%s nonExternalRpcPrefixes=%s",
                projectRoot.toAbsolutePath(),
                endpointPathFilter,
                entryMethodFilter,
                maxDepth,
                maxPathsPerEndpoint,
                this.externalRpcPrefixes,
                this.nonExternalRpcPrefixes
        );

        BeanRegistry beanRegistry = buildBeanRegistry(javaModel, xmlModel);
        InjectionRegistry injectionRegistry = buildInjectionRegistry(javaModel, xmlModel, beanRegistry);
        Map<String, MethodModel> methodsById = collectMethods(javaModel);
        List<CallEdge> edges = buildCallEdges(javaModel, methodsById, injectionRegistry);
        List<Endpoint> endpoints = buildEndpoints(javaModel, xmlModel, beanRegistry);
        List<Endpoint> selectedEndpoints = new ArrayList<Endpoint>();
        if (!isBlank(endpointPathFilter)) {
            selectedEndpoints.addAll(selectEndpoints(endpoints, endpointPathFilter));
        } else if (isBlank(entryMethodFilter)) {
            selectedEndpoints.addAll(endpoints);
        }
        debugLogger.log("ENDPOINTS total=%d selected=%d", endpoints.size(), selectedEndpoints.size());
        Map<String, List<CallEdge>> edgesFrom = edges.stream()
                .collect(Collectors.groupingBy(e -> e.from, LinkedHashMap::new, Collectors.toList()));

        List<EndpointPaths> endpointPaths = new ArrayList<>();
        for (Endpoint endpoint : selectedEndpoints) {
            List<List<String>> paths = enumeratePaths(endpoint.entryMethodId, edgesFrom, maxDepth, maxPathsPerEndpoint);
            endpointPaths.add(new EndpointPaths(endpoint.path, endpoint.httpMethods, endpoint.source, endpoint.entryMethodId, paths));
        }
        for (String entryMethodId : selectEntryMethods(entryMethodFilter, methodsById, javaModel)) {
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

        return new AnalysisReport(
                Instant.now().toString(),
                projectRoot.toAbsolutePath().toString(),
                methodDisplay,
                filteredEdges,
                endpointPaths,
                buildExternalDependencyTrees(endpointPaths, filteredEdges, methodDisplay)
        );
        } finally {
            this.externalRpcPrefixes = previousExternalRpcPrefixes;
            this.nonExternalRpcPrefixes = previousNonExternalRpcPrefixes;
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

    private List<ExternalDependencyTree> buildExternalDependencyTrees(List<EndpointPaths> endpointPaths,
                                                                      List<CallEdge> edges,
                                                                      Map<String, String> methodDisplay) {
        Map<String, CallEdge> edgeByKey = new LinkedHashMap<String, CallEdge>();
        for (CallEdge edge : edges) {
            edgeByKey.put(edge.from + "->" + edge.to, edge);
        }

        List<ExternalDependencyTree> trees = new ArrayList<ExternalDependencyTree>();
        for (EndpointPaths endpointPath : endpointPaths) {
            MutableDependencyNode root = new MutableDependencyNode(
                    endpointPath.entryMethodId,
                    methodDisplay.get(endpointPath.entryMethodId)
            );
            boolean hasExternalBranch = false;

            for (List<String> path : endpointPath.paths) {
                if (path == null || path.size() < 2) {
                    continue;
                }

                int edgeCount = path.size() - 1;
                boolean[] keepEdge = new boolean[edgeCount];
                boolean downstreamExternal = false;

                for (int i = edgeCount - 1; i >= 0; i--) {
                    String from = path.get(i);
                    String to = path.get(i + 1);
                    CallEdge edge = edgeByKey.get(from + "->" + to);
                    if (edge != null && !edge.externalDependencyTypes.isEmpty()) {
                        downstreamExternal = true;
                    }
                    keepEdge[i] = downstreamExternal;
                }

                if (!downstreamExternal) {
                    continue;
                }
                hasExternalBranch = true;

                MutableDependencyNode current = root;
                for (int i = 0; i < edgeCount; i++) {
                    if (!keepEdge[i]) {
                        continue;
                    }
                    String from = path.get(i);
                    String to = path.get(i + 1);
                    CallEdge edge = edgeByKey.get(from + "->" + to);
                    List<String> dependencyTypes = edge == null ? listOf() : edge.externalDependencyTypes;
                    String reason = edge == null ? "" : edge.reason;
                    int line = edge == null ? -1 : edge.line;
                    current = current.addChild(
                            from,
                            to,
                            methodDisplay.get(to),
                            dependencyTypes,
                            reason,
                            line
                    );
                }
            }

            if (!hasExternalBranch) {
                continue;
            }
            trees.add(new ExternalDependencyTree(
                    endpointPath.path,
                    endpointPath.httpMethods,
                    endpointPath.source,
                    endpointPath.entryMethodId,
                    root.toImmutable()
            ));
        }
        debugLogger.log("EXTERNAL_DEP_TREES count=%d", trees.size());
        return trees;
    }

    private List<String> classifyExternalDependencyTypes(String calleeMethodId,
                                                         Map<String, MethodModel> methodsById,
                                                         JavaModel javaModel) {
        MethodModel calleeMethod = methodsById.get(calleeMethodId);
        MethodIdInfo idInfo = MethodIdInfo.parse(calleeMethodId);
        if (calleeMethod == null && idInfo == null) {
            return listOf();
        }

        String className = calleeMethod != null ? calleeMethod.className : idInfo.className;
        String simpleClassName = simpleName(className);
        String packageName = "";
        if (!isBlank(className) && className.contains(".")) {
            packageName = className.substring(0, className.lastIndexOf('.')).toLowerCase(Locale.ROOT);
        }
        ClassModel calleeClass = javaModel.classesByName.get(className);
        String methodName = calleeMethod != null ? calleeMethod.name : idInfo.methodName;

        Set<String> classAnnotations = calleeClass == null ? Collections.<String>emptySet() : calleeClass.annotations;
        Set<String> methodAnnotations = calleeMethod == null ? Collections.<String>emptySet() : calleeMethod.annotations;

        boolean sourceClass = calleeClass != null;
        boolean jarClass = !sourceClass && !isBlank(className);
        boolean jdkClass = startsWithAnyIgnoreCase(className, "java.", "javax.", "jakarta.", "sun.");
        boolean matchedExternalPrefix = startsWithAnyPrefixIgnoreCase(className, externalRpcPrefixes);
        boolean matchedNonExternalPrefix = startsWithAnyPrefixIgnoreCase(className, nonExternalRpcPrefixes);
        boolean forceExternalRpc = jarClass && matchedExternalPrefix;
        boolean forceNonExternalRpc = jarClass && matchedNonExternalPrefix;
        boolean thriftClass = containsAnyIgnoreCase(className, ".thrift.", "thrift.", "$client", "$iface")
                || containsAnyIgnoreCase(simpleClassName, "Thrift", "Iface", "AsyncClient");

        boolean db = false;
        boolean cache = false;
        boolean rpc = false;

        if (classAnnotations.contains("Repository")
                || classAnnotations.contains("Mapper")
                || classAnnotations.contains("Dao")
                || endsWithAnyIgnoreCase(simpleClassName, "Repository", "Repo", "Dao", "Mapper")
                || containsAnyIgnoreCase(packageName, ".repo", ".repository", ".dao", ".mapper", ".mybatis")) {
            db = true;
        }

        if (containsAnyIgnoreCase(simpleClassName, "Cache", "Redis", "Caffeine", "Ehcache", "Memcache")
                || containsAnyIgnoreCase(packageName, ".cache", ".redis", ".caffeine", ".ehcache", ".memcache")
                || methodAnnotations.contains("Cacheable")
                || methodAnnotations.contains("CachePut")
                || methodAnnotations.contains("CacheEvict")) {
            cache = true;
        }

        if (classAnnotations.contains("FeignClient")
                || containsAnyIgnoreCase(simpleClassName, "Feign", "Rpc", "Grpc", "Remote", "Gateway", "Client")
                || containsAnyIgnoreCase(packageName, ".rpc", ".remote", ".feign", ".grpc", ".client", ".gateway")) {
            rpc = true;
        }
        if (thriftClass) {
            rpc = true;
        }
        if (jarClass && !jdkClass && !forceNonExternalRpc && !db && !cache) {
            rpc = true;
        }
        if (forceExternalRpc) {
            rpc = true;
        }
        if (forceNonExternalRpc) {
            rpc = false;
        }
        if (forceExternalRpc && forceNonExternalRpc) {
            debugLogger.log("RPC_PREFIX_CONFLICT class=%s external=%s nonExternal=%s",
                    className,
                    externalRpcPrefixes,
                    nonExternalRpcPrefixes);
        }
        if (sourceClass && (matchedExternalPrefix || matchedNonExternalPrefix)) {
            debugLogger.log("RPC_PREFIX_IGNORED_NON_JAR class=%s", className);
        }

        if (db && containsAnyIgnoreCase(methodName, "getFromCache", "cache")) {
            cache = true;
        }

        List<String> kinds = new ArrayList<String>();
        if (db) {
            kinds.add("DB");
        }
        if (cache) {
            kinds.add("CACHE");
        }
        if (rpc) {
            kinds.add("RPC");
        }
        return kinds;
    }

    private static boolean endsWithAnyIgnoreCase(String raw, String... suffixes) {
        if (raw == null) {
            return false;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        for (String suffix : suffixes) {
            if (lower.endsWith(suffix.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAnyIgnoreCase(String raw, String... tokens) {
        if (raw == null) {
            return false;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (lower.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
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
                        String key = methodModel.id + "->" + candidate.getKey();
                        CallEdgeAccumulator accumulator = edgeMap.get(key);
                        if (accumulator == null) {
                            accumulator = new CallEdgeAccumulator(methodModel.id, candidate.getKey(), call.line);
                            edgeMap.put(key, accumulator);
                        } else {
                            accumulator.occurrences++;
                            debugLogger.log(
                                    "EDGE_DEDUP from=%s to=%s line=%d occurrences=%d",
                                    accumulator.from,
                                    accumulator.to,
                                    call.line,
                                    accumulator.occurrences
                            );
                        }
                        accumulator.reasons.add(candidate.getValue());
                        debugLogger.log(
                                "EDGE_ADD from=%s to=%s reason=%s line=%d",
                                methodModel.id,
                                candidate.getKey(),
                                candidate.getValue(),
                                call.line
                        );
                    }
                }
            }
        }

        List<CallEdge> edges = new ArrayList<CallEdge>();
        for (CallEdgeAccumulator accumulator : edgeMap.values()) {
            List<String> dependencyTypes = classifyExternalDependencyTypes(accumulator.to, methodsById, javaModel);
            if (!dependencyTypes.isEmpty()) {
                debugLogger.log(
                        "EDGE_EXTERNAL from=%s to=%s types=%s",
                        accumulator.from,
                        accumulator.to,
                        dependencyTypes
                );
            }
            edges.add(new CallEdge(
                    accumulator.from,
                    accumulator.to,
                    String.join("|", accumulator.reasons),
                    accumulator.line,
                    dependencyTypes
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

    private Map<String, String> resolveCallCandidates(ClassModel classModel,
                                                      MethodModel methodModel,
                                                      CallSite call,
                                                      JavaModel javaModel,
                                                      Map<String, MethodModel> methodsById,
                                                      InjectionRegistry injectionRegistry,
                                                      int depth) {
        if (depth > 6) {
            debugLogger.log("RESOLVE_DEPTH_GUARD from=%s call=%s/%d", methodModel.id, call.methodName, call.argumentCount);
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

        candidates.add("java.lang." + normalized);
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

    private static boolean startsWithAnyPrefixIgnoreCase(String raw, Collection<String> prefixes) {
        if (isBlank(raw) || prefixes == null || prefixes.isEmpty()) {
            return false;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        for (String prefix : prefixes) {
            if (isBlank(prefix)) {
                continue;
            }
            String normalizedPrefix = prefix.toLowerCase(Locale.ROOT);
            if (lower.equals(normalizedPrefix) || lower.startsWith(normalizedPrefix + ".")) {
                return true;
            }
        }
        return false;
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
            int maxDepth = 8;
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
