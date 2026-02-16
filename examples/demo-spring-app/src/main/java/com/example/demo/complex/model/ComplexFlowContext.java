package com.example.demo.complex.model;

import com.example.demo.complex.pattern.Visitable;
import com.example.demo.complex.pattern.Visitor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ComplexFlowContext<K, V> implements Visitable<ComplexFlowContext<K, V>> {
    private final K requestKey;
    private V payload;
    private final List<String> trace = new ArrayList<String>();
    private final Map<String, Object> attributes = new LinkedHashMap<String, Object>();

    private ComplexFlowContext(K requestKey, V payload) {
        this.requestKey = requestKey;
        this.payload = payload;
    }

    public static <K, V> ComplexFlowContext<K, V> of(K requestKey, V payload) {
        return new ComplexFlowContext<K, V>(requestKey, payload);
    }

    public K getRequestKey() {
        return requestKey;
    }

    public V getPayload() {
        return payload;
    }

    public void setPayload(V payload) {
        this.payload = payload;
    }

    public void addTrace(String node) {
        if (node != null && !node.trim().isEmpty()) {
            trace.add(node);
        }
    }

    public List<String> getTrace() {
        return trace;
    }

    public <T> void putAttribute(String key, T value) {
        attributes.put(key, value);
    }

    public <T> T getAttribute(String key, Class<T> type) {
        Object value = attributes.get(key);
        if (value == null) {
            return null;
        }
        return type.cast(value);
    }

    @Override
    public <R> R accept(Visitor<ComplexFlowContext<K, V>, R> visitor) {
        return visitor.visit(this);
    }
}
