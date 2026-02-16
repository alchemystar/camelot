package com.camelot.analyzer.sandboxdemo;

public class InputSanitizer {
    private WhitespaceNormalizer whitespaceNormalizer;

    public String clean(String input) {
        String normalized = whitespaceNormalizer.normalize(input);
        if (normalized.isEmpty()) {
            return "EMPTY";
        }
        return normalized;
    }
}
