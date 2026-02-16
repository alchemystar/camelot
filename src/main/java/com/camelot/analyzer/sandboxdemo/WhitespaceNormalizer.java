package com.camelot.analyzer.sandboxdemo;

public class WhitespaceNormalizer {
    public String normalize(String input) {
        String source = input;
        if (source == null) {
            return "";
        }
        return source.replaceAll("\\s+", " ").trim();
    }
}
