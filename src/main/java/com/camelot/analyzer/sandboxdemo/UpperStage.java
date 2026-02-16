package com.camelot.analyzer.sandboxdemo;

public class UpperStage implements DemoStage {
    @Override
    public String apply(String input) {
        String source = input;
        if (source == null) {
            source = "";
        }
        source = source.trim();
        return source.toUpperCase();
    }
}
