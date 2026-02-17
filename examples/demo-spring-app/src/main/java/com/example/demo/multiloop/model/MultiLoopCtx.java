package com.example.demo.multiloop.model;

import java.util.ArrayList;
import java.util.List;

public class MultiLoopCtx {

    private final List<String> traces = new ArrayList<String>();

    public void addTrace(String trace) {
        if (trace == null || trace.trim().isEmpty()) {
            return;
        }
        traces.add(trace);
    }

    public int size() {
        return traces.size();
    }

    public List<String> getTraces() {
        return traces;
    }
}
