package com.camelot.runtime.bootstrap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class CallChainCollector {

    private final ThreadLocal<Deque<String>> callStack = new ThreadLocal<Deque<String>>() {
        @Override
        protected Deque<String> initialValue() {
            return new ArrayDeque<String>();
        }
    };
    private final Set<String> nodes = new LinkedHashSet<String>();
    private final Set<Edge> edges = new LinkedHashSet<Edge>();

    void clear() {
        synchronized (this) {
            nodes.clear();
            edges.clear();
        }
        callStack.get().clear();
    }

    void enter(String node) {
        if (node == null || node.trim().isEmpty()) {
            return;
        }
        Deque<String> stack = callStack.get();
        String current = node.trim();
        synchronized (this) {
            nodes.add(current);
            if (!stack.isEmpty()) {
                edges.add(new Edge(stack.peek(), current));
            }
        }
        stack.push(current);
    }

    void exit() {
        Deque<String> stack = callStack.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
    }

    String toDot() {
        List<String> snapshotNodes;
        List<Edge> snapshotEdges;
        synchronized (this) {
            snapshotNodes = new ArrayList<String>(nodes);
            snapshotEdges = new ArrayList<Edge>(edges);
        }
        StringBuilder dot = new StringBuilder(4096);
        dot.append("digraph CallChain {\n");
        dot.append("  rankdir=LR;\n");
        dot.append("  node [shape=box, fontsize=10];\n");
        for (String node : snapshotNodes) {
            String escaped = escape(node);
            dot.append("  \"").append(escaped).append("\";\n");
        }
        for (Edge edge : snapshotEdges) {
            dot.append("  \"")
                    .append(escape(edge.from))
                    .append("\" -> \"")
                    .append(escape(edge.to))
                    .append("\";\n");
        }
        dot.append("}\n");
        return dot.toString();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
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
            Edge that = (Edge) other;
            return from.equals(that.from) && to.equals(that.to);
        }

        @Override
        public int hashCode() {
            int result = from.hashCode();
            result = 31 * result + to.hashCode();
            return result;
        }
    }
}
