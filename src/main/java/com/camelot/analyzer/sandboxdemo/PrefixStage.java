package com.camelot.analyzer.sandboxdemo;

public class PrefixStage implements DemoStage {
    @Override
    public String apply(String input) {
        String source = input;
        if (source == null) {
            source = "";
        }
        return "PIPE-" + source;
    }
}
