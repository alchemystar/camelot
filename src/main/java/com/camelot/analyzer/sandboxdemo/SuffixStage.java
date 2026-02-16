package com.camelot.analyzer.sandboxdemo;

public class SuffixStage implements DemoStage {
    @Override
    public String apply(String input) {
        String source = input;
        if (source == null) {
            source = "";
        }
        return source + "-OK";
    }
}
