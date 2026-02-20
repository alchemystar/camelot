package com.camelot.runtime.bootstrap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class MethodPathRuntime {

    private static final ThreadLocal<Deque<String>> CALL_STACK = new ThreadLocal<Deque<String>>() {
        @Override
        protected Deque<String> initialValue() {
            return new ArrayDeque<String>();
        }
    };
    private static final Set<String> NODES = new LinkedHashSet<String>();
    private static final Set<Edge> EDGES = new LinkedHashSet<Edge>();
    private static volatile boolean enabled = false;

    private MethodPathRuntime() {
    }

    public static void enable() {
        enabled = true;
    }

    public static void disable() {
        enabled = false;
        CALL_STACK.get().clear();
    }

    public static void clear() {
        synchronized (MethodPathRuntime.class) {
            NODES.clear();
            EDGES.clear();
        }
        CALL_STACK.get().clear();
    }

    public static void enter(String className, String methodName, int argCount) {
        if (!enabled) {
            return;
        }
        String node = normalizeNode(className, methodName, argCount);
        Deque<String> stack = CALL_STACK.get();
        synchronized (MethodPathRuntime.class) {
            NODES.add(node);
            if (!stack.isEmpty()) {
                EDGES.add(new Edge(stack.peek(), node));
            }
        }
        stack.push(node);
    }

    public static void exit() {
        if (!enabled) {
            return;
        }
        Deque<String> stack = CALL_STACK.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
    }

    public static String toDot() {
        List<String> nodes;
        List<Edge> edges;
        synchronized (MethodPathRuntime.class) {
            nodes = new ArrayList<String>(NODES);
            edges = new ArrayList<Edge>(EDGES);
        }
        if (nodes.isEmpty() && edges.isEmpty()) {
            return "digraph CallChain {\n}\n";
        }
        StringBuilder dot = new StringBuilder(4096);
        dot.append("digraph CallChain {\n");
        dot.append("  rankdir=LR;\n");
        dot.append("  node [shape=box, fontsize=10];\n");
        for (String node : nodes) {
            dot.append("  \"").append(escape(node)).append("\";\n");
        }
        for (Edge edge : edges) {
            dot.append("  \"")
                    .append(escape(edge.from))
                    .append("\" -> \"")
                    .append(escape(edge.to))
                    .append("\";\n");
        }
        dot.append("}\n");
        return dot.toString();
    }

    public static boolean hasData() {
        synchronized (MethodPathRuntime.class) {
            return !NODES.isEmpty() || !EDGES.isEmpty();
        }
    }

    private static String normalizeNode(String className, String methodName, int argCount) {
        String cleanClass = className == null ? "unknown" : className.trim();
        String cleanMethod = methodName == null ? "unknown" : methodName.trim();
        int cglib = cleanClass.indexOf("$$");
        if (cglib > 0) {
            cleanClass = cleanClass.substring(0, cglib);
        }
        return cleanClass + "#" + cleanMethod + "/" + argCount;
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class Edge {
        private final String from;
        private final String to;

        private Edge(String from, String to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Edge)) {
                return false;
            }
            Edge edge = (Edge) other;
            return from.equals(edge.from) && to.equals(edge.to);
        }

        @Override
        public int hashCode() {
            int result = from.hashCode();
            result = 31 * result + to.hashCode();
            return result;
        }
    }
}
