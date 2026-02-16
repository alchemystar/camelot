package com.example.demo.interfacepipe.model;

import java.util.ArrayList;
import java.util.List;

public class FlowCtx {
    private final List<String> trace = new ArrayList<String>();

    public void addTrace(String node) {
        if (node != null && !node.trim().isEmpty()) {
            trace.add(node);
        }
    }

    public List<String> getTrace() {
        return trace;
    }
}
