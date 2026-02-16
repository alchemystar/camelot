package com.camelot.analyzer;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

public class DependencyAnalysisModule {
    private final SpringCallPathAnalyzer codeAnalyzer;

    public DependencyAnalysisModule(SpringCallPathAnalyzer codeAnalyzer) {
        this.codeAnalyzer = codeAnalyzer;
    }

    public SpringCallPathAnalyzer.AnalysisReport analyzeWithDependencies(java.nio.file.Path projectRoot,
                                                                         int maxDepth,
                                                                         int maxPathsPerEndpoint,
                                                                         String endpointPathFilter,
                                                                         String entryMethodFilter,
                                                                         SpringCallPathAnalyzer.DebugLogger debugLogger,
                                                                         Set<String> externalRpcPrefixes,
                                                                         Set<String> nonExternalRpcPrefixes)
            throws IOException {
        SpringCallPathAnalyzer.DebugLogger logger = debugLogger == null
                ? SpringCallPathAnalyzer.DebugLogger.disabled()
                : debugLogger;
        Set<String> normalizedExternalRpcPrefixes = normalizePackagePrefixes(externalRpcPrefixes);
        Set<String> normalizedNonExternalRpcPrefixes = normalizePackagePrefixes(nonExternalRpcPrefixes);

        logger.log("DEPENDENCY_ANALYSIS_START externalRpcPrefixes=%s nonExternalRpcPrefixes=%s",
                normalizedExternalRpcPrefixes,
                normalizedNonExternalRpcPrefixes);

        SpringCallPathAnalyzer.CodeAnalysisResult codeResult = codeAnalyzer.analyzeCode(
                projectRoot,
                maxDepth,
                maxPathsPerEndpoint,
                endpointPathFilter,
                entryMethodFilter,
                logger
        );

        List<SpringCallPathAnalyzer.CallEdge> enrichedEdges = enrichDependencyTypes(
                codeResult.edges,
                codeResult.methodsById,
                codeResult.javaModel,
                normalizedExternalRpcPrefixes,
                normalizedNonExternalRpcPrefixes,
                logger
        );
        List<SpringCallPathAnalyzer.ExternalDependencyTree> trees = buildExternalDependencyTrees(
                codeResult.endpoints,
                enrichedEdges,
                codeResult.methodDisplay,
                logger
        );
        List<SpringCallPathAnalyzer.BeanDependencyEdge> beanDependencies = buildBeanDependencyEdges(
                codeResult,
                enrichedEdges,
                logger
        );

        logger.log("DEPENDENCY_ANALYSIS_DONE edges=%d trees=%d beanDeps=%d",
                enrichedEdges.size(),
                trees.size(),
                beanDependencies.size());
        return new SpringCallPathAnalyzer.AnalysisReport(
                Instant.now().toString(),
                codeResult.projectRoot,
                codeResult.methodDisplay,
                enrichedEdges,
                codeResult.endpoints,
                beanDependencies,
                trees
        );
    }

    private List<SpringCallPathAnalyzer.BeanDependencyEdge> buildBeanDependencyEdges(
            SpringCallPathAnalyzer.CodeAnalysisResult codeResult,
            List<SpringCallPathAnalyzer.CallEdge> edges,
            SpringCallPathAnalyzer.DebugLogger debugLogger) {
        if (codeResult == null || edges == null || edges.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> beanClasses = codeResult.beanClassNames == null
                ? Collections.<String>emptySet()
                : codeResult.beanClassNames;
        if (beanClasses.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, List<SpringCallPathAnalyzer.CallEdge>> edgesFrom =
                new LinkedHashMap<String, List<SpringCallPathAnalyzer.CallEdge>>();
        for (SpringCallPathAnalyzer.CallEdge edge : edges) {
            edgesFrom.computeIfAbsent(edge.from, k -> new ArrayList<SpringCallPathAnalyzer.CallEdge>()).add(edge);
        }
        for (List<SpringCallPathAnalyzer.CallEdge> outgoing : edgesFrom.values()) {
            outgoing.sort((a, b) -> {
                int lineCompare = Integer.compare(a.line, b.line);
                if (lineCompare != 0) {
                    return lineCompare;
                }
                int toCompare = a.to.compareTo(b.to);
                if (toCompare != 0) {
                    return toCompare;
                }
                return a.reason.compareTo(b.reason);
            });
        }

        Map<String, BeanDependencyAccumulator> accumulators = new LinkedHashMap<String, BeanDependencyAccumulator>();
        Map<String, List<String>> methodsByClass = new LinkedHashMap<String, List<String>>();
        for (SpringCallPathAnalyzer.MethodModel method : codeResult.methodsById.values()) {
            methodsByClass.computeIfAbsent(method.className, k -> new ArrayList<String>()).add(method.id);
        }

        for (String sourceBean : beanClasses) {
            List<String> startMethods = methodsByClass.get(sourceBean);
            if (startMethods == null || startMethods.isEmpty()) {
                continue;
            }
            for (String startMethod : startMethods) {
                Deque<MethodHop> queue = new ArrayDeque<MethodHop>();
                Map<String, Integer> bestDepth = new LinkedHashMap<String, Integer>();
                queue.addLast(new MethodHop(startMethod, 0));
                bestDepth.put(startMethod, Integer.valueOf(0));

                while (!queue.isEmpty()) {
                    MethodHop current = queue.removeFirst();
                    List<SpringCallPathAnalyzer.CallEdge> outgoing = edgesFrom.get(current.methodId);
                    if (outgoing == null || outgoing.isEmpty()) {
                        continue;
                    }
                    for (SpringCallPathAnalyzer.CallEdge edge : outgoing) {
                        String targetClass = toClassName(edge.to, codeResult.methodsById);
                        if (isBlank(targetClass)) {
                            continue;
                        }
                        int nextDepth = current.depth + 1;
                        if (beanClasses.contains(targetClass)) {
                            if (!sourceBean.equals(targetClass)) {
                                String key = sourceBean + "->" + targetClass;
                                BeanDependencyAccumulator acc = accumulators.get(key);
                                if (acc == null) {
                                    acc = new BeanDependencyAccumulator(sourceBean, targetClass);
                                    accumulators.put(key, acc);
                                }
                                acc.pathCount++;
                                acc.minHops = Math.min(acc.minHops, nextDepth);
                                if (!isBlank(edge.reason) && acc.sampleReasons.size() < 5) {
                                    acc.sampleReasons.add(edge.reason);
                                }
                            }
                            continue;
                        }

                        Integer previousBest = bestDepth.get(edge.to);
                        if (previousBest != null && previousBest.intValue() <= nextDepth) {
                            continue;
                        }
                        bestDepth.put(edge.to, Integer.valueOf(nextDepth));
                        queue.addLast(new MethodHop(edge.to, nextDepth));
                    }
                }
            }
        }

        List<SpringCallPathAnalyzer.BeanDependencyEdge> result = new ArrayList<SpringCallPathAnalyzer.BeanDependencyEdge>();
        for (BeanDependencyAccumulator acc : accumulators.values()) {
            if (acc.pathCount <= 0 || acc.minHops == Integer.MAX_VALUE) {
                continue;
            }
            result.add(new SpringCallPathAnalyzer.BeanDependencyEdge(
                    acc.fromBeanClass,
                    acc.toBeanClass,
                    acc.minHops,
                    acc.pathCount,
                    new ArrayList<String>(acc.sampleReasons)
            ));
        }
        result.sort((a, b) -> {
            int fromCompare = a.fromBeanClass.compareTo(b.fromBeanClass);
            if (fromCompare != 0) {
                return fromCompare;
            }
            int toCompare = a.toBeanClass.compareTo(b.toBeanClass);
            if (toCompare != 0) {
                return toCompare;
            }
            return Integer.compare(a.minHops, b.minHops);
        });
        debugLogger.log("BEAN_DEP_GRAPH edges=%d beans=%d", result.size(), beanClasses.size());
        return result;
    }

    private String toClassName(String methodId, Map<String, SpringCallPathAnalyzer.MethodModel> methodsById) {
        SpringCallPathAnalyzer.MethodModel methodModel = methodsById.get(methodId);
        if (methodModel != null) {
            return methodModel.className;
        }
        SpringCallPathAnalyzer.MethodIdInfo info = SpringCallPathAnalyzer.MethodIdInfo.parse(methodId);
        return info == null ? "" : info.className;
    }

    private List<SpringCallPathAnalyzer.CallEdge> enrichDependencyTypes(
            List<SpringCallPathAnalyzer.CallEdge> codeEdges,
            Map<String, SpringCallPathAnalyzer.MethodModel> methodsById,
            SpringCallPathAnalyzer.JavaModel javaModel,
            Set<String> externalRpcPrefixes,
            Set<String> nonExternalRpcPrefixes,
            SpringCallPathAnalyzer.DebugLogger debugLogger) {
        List<SpringCallPathAnalyzer.CallEdge> edges = new ArrayList<SpringCallPathAnalyzer.CallEdge>();
        for (SpringCallPathAnalyzer.CallEdge edge : codeEdges) {
            List<String> dependencyTypes = classifyExternalDependencyTypes(
                    edge.to,
                    edge.reason,
                    methodsById,
                    javaModel,
                    externalRpcPrefixes,
                    nonExternalRpcPrefixes,
                    debugLogger
            );
            if (!dependencyTypes.isEmpty()) {
                debugLogger.log("EDGE_EXTERNAL from=%s to=%s types=%s", edge.from, edge.to, dependencyTypes);
            }
            edges.add(new SpringCallPathAnalyzer.CallEdge(
                    edge.from,
                    edge.to,
                    edge.reason,
                    edge.line,
                    dependencyTypes
            ));
        }
        return edges;
    }

    private List<SpringCallPathAnalyzer.ExternalDependencyTree> buildExternalDependencyTrees(
            List<SpringCallPathAnalyzer.EndpointPaths> endpointPaths,
            List<SpringCallPathAnalyzer.CallEdge> edges,
            Map<String, String> methodDisplay,
            SpringCallPathAnalyzer.DebugLogger debugLogger) {
        Map<String, SpringCallPathAnalyzer.CallEdge> edgeByKey = new LinkedHashMap<String, SpringCallPathAnalyzer.CallEdge>();
        for (SpringCallPathAnalyzer.CallEdge edge : edges) {
            edgeByKey.put(edge.from + "->" + edge.to, edge);
        }

        List<SpringCallPathAnalyzer.ExternalDependencyTree> trees =
                new ArrayList<SpringCallPathAnalyzer.ExternalDependencyTree>();
        for (SpringCallPathAnalyzer.EndpointPaths endpointPath : endpointPaths) {
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
                    SpringCallPathAnalyzer.CallEdge edge = edgeByKey.get(from + "->" + to);
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
                    SpringCallPathAnalyzer.CallEdge edge = edgeByKey.get(from + "->" + to);
                    List<String> dependencyTypes = edge == null
                            ? new ArrayList<String>()
                            : edge.externalDependencyTypes;
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
            trees.add(new SpringCallPathAnalyzer.ExternalDependencyTree(
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
                                                         String callReason,
                                                         Map<String, SpringCallPathAnalyzer.MethodModel> methodsById,
                                                         SpringCallPathAnalyzer.JavaModel javaModel,
                                                         Set<String> externalRpcPrefixes,
                                                         Set<String> nonExternalRpcPrefixes,
                                                         SpringCallPathAnalyzer.DebugLogger debugLogger) {
        if (!isBeanInvocation(callReason)) {
            if (!isBlank(callReason)) {
                debugLogger.log("DEPENDENCY_SKIP_NON_BEAN_CALL callee=%s reason=%s", calleeMethodId, callReason);
            }
            return new ArrayList<String>();
        }
        SpringCallPathAnalyzer.MethodModel calleeMethod = methodsById.get(calleeMethodId);
        SpringCallPathAnalyzer.MethodIdInfo idInfo = SpringCallPathAnalyzer.MethodIdInfo.parse(calleeMethodId);
        if (calleeMethod == null && idInfo == null) {
            return new ArrayList<String>();
        }

        String className = calleeMethod != null ? calleeMethod.className : idInfo.className;
        String simpleClassName = simpleName(className);
        String packageName = "";
        if (!isBlank(className) && className.contains(".")) {
            packageName = className.substring(0, className.lastIndexOf('.')).toLowerCase(Locale.ROOT);
        }
        SpringCallPathAnalyzer.ClassModel calleeClass = javaModel.classesByName.get(className);
        String methodName = calleeMethod != null ? calleeMethod.name : idInfo.methodName;

        Set<String> classAnnotations = calleeClass == null
                ? Collections.<String>emptySet()
                : calleeClass.annotations;
        Set<String> methodAnnotations = calleeMethod == null
                ? Collections.<String>emptySet()
                : calleeMethod.annotations;

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

    private static boolean isBeanInvocation(String reasonSummary) {
        if (isBlank(reasonSummary)) {
            return false;
        }
        for (String reasonToken : reasonSummary.split("\\|")) {
            String token = normalizeReasonToken(reasonToken);
            if (token.startsWith("ANNOTATION:") || token.startsWith("XML:") || token.startsWith("XML/ANNOTATION:")) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeReasonToken(String rawToken) {
        if (rawToken == null) {
            return "";
        }
        String token = rawToken.trim();
        boolean changed = true;
        while (changed) {
            changed = false;
            if (token.startsWith("CHAIN:")) {
                token = token.substring("CHAIN:".length()).trim();
                changed = true;
            }
            if (token.startsWith("PIPELINE:")) {
                token = token.substring("PIPELINE:".length()).trim();
                changed = true;
            }
        }
        return token;
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

    private static String simpleName(String fqcnOrSimple) {
        if (fqcnOrSimple == null) {
            return "";
        }
        int idx = fqcnOrSimple.lastIndexOf('.');
        return idx >= 0 ? fqcnOrSimple.substring(idx + 1) : fqcnOrSimple;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static class MethodHop {
        private final String methodId;
        private final int depth;

        private MethodHop(String methodId, int depth) {
            this.methodId = methodId;
            this.depth = depth;
        }
    }

    private static class BeanDependencyAccumulator {
        private final String fromBeanClass;
        private final String toBeanClass;
        private int minHops = Integer.MAX_VALUE;
        private int pathCount = 0;
        private final Set<String> sampleReasons = new LinkedHashSet<String>();

        private BeanDependencyAccumulator(String fromBeanClass, String toBeanClass) {
            this.fromBeanClass = fromBeanClass;
            this.toBeanClass = toBeanClass;
        }
    }

    private static class MutableDependencyNode {
        private final String methodId;
        private final String methodDisplay;
        private final Map<String, MutableDependencyEdge> childrenByTo = new LinkedHashMap<String, MutableDependencyEdge>();

        private MutableDependencyNode(String methodId, String methodDisplay) {
            this.methodId = methodId;
            this.methodDisplay = methodDisplay;
        }

        private MutableDependencyNode addChild(String fromMethodId,
                                               String toMethodId,
                                               String toMethodDisplay,
                                               List<String> externalDependencyTypes,
                                               String callReason,
                                               int line) {
            MutableDependencyEdge edge = childrenByTo.get(toMethodId);
            if (edge == null) {
                MutableDependencyNode childNode = new MutableDependencyNode(toMethodId, toMethodDisplay);
                edge = new MutableDependencyEdge(
                        fromMethodId,
                        toMethodId,
                        toMethodDisplay,
                        externalDependencyTypes,
                        callReason,
                        line,
                        childNode
                );
                childrenByTo.put(toMethodId, edge);
            } else {
                edge.merge(externalDependencyTypes, callReason, line);
                if (isBlank(edge.toMethodDisplay) && !isBlank(toMethodDisplay)) {
                    edge.toMethodDisplay = toMethodDisplay;
                }
            }
            return edge.child;
        }

        private SpringCallPathAnalyzer.DependencyNode toImmutable() {
            List<SpringCallPathAnalyzer.DependencyEdge> children = new ArrayList<SpringCallPathAnalyzer.DependencyEdge>();
            for (MutableDependencyEdge edge : childrenByTo.values()) {
                children.add(edge.toImmutable());
            }
            return new SpringCallPathAnalyzer.DependencyNode(methodId, methodDisplay, children);
        }
    }

    private static class MutableDependencyEdge {
        private final String fromMethodId;
        private final String toMethodId;
        private String toMethodDisplay;
        private final Set<String> externalDependencyTypes;
        private final Set<String> callReasons;
        private int line;
        private final MutableDependencyNode child;

        private MutableDependencyEdge(String fromMethodId,
                                      String toMethodId,
                                      String toMethodDisplay,
                                      List<String> externalDependencyTypes,
                                      String callReason,
                                      int line,
                                      MutableDependencyNode child) {
            this.fromMethodId = fromMethodId;
            this.toMethodId = toMethodId;
            this.toMethodDisplay = toMethodDisplay;
            this.externalDependencyTypes = new LinkedHashSet<String>();
            if (externalDependencyTypes != null) {
                this.externalDependencyTypes.addAll(externalDependencyTypes);
            }
            this.callReasons = new LinkedHashSet<String>();
            if (!isBlank(callReason)) {
                this.callReasons.add(callReason);
            }
            this.line = line;
            this.child = child;
        }

        private void merge(List<String> dependencyTypes, String callReason, int line) {
            if (dependencyTypes != null) {
                this.externalDependencyTypes.addAll(dependencyTypes);
            }
            if (!isBlank(callReason)) {
                this.callReasons.add(callReason);
            }
            if (this.line <= 0 && line > 0) {
                this.line = line;
            }
        }

        private SpringCallPathAnalyzer.DependencyEdge toImmutable() {
            String reason = "";
            if (!callReasons.isEmpty()) {
                reason = String.join("|", callReasons);
            }
            return new SpringCallPathAnalyzer.DependencyEdge(
                    fromMethodId,
                    toMethodId,
                    toMethodDisplay,
                    new ArrayList<String>(externalDependencyTypes),
                    reason,
                    line,
                    child.toImmutable()
            );
        }
    }

}
