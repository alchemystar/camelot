package com.camelot.analyzer.sandboxdemo;

public class AuditRecorder {
    public void beforeStart(String input) {
        if (input == null) {
            return;
        }
        input.length();
    }

    public void afterPipeline(String output) {
        if (output == null) {
            return;
        }
        output.length();
    }

    public void afterFinish(String output) {
        if (output == null) {
            return;
        }
        output.hashCode();
    }
}
