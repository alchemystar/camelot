package com.camelot.analyzer.sandboxdemo;

public class FlowContainer {
    private final DemoPipeline pipeline;
    private final AuditRecorder auditRecorder;

    private FlowContainer(DemoPipeline pipeline, AuditRecorder auditRecorder) {
        this.pipeline = pipeline;
        this.auditRecorder = auditRecorder;
    }

    public static Builder a() {
        return new Builder();
    }

    public String start(String input) {
        auditRecorder.beforeStart(input);
        String output = pipeline.execute(input);
        auditRecorder.afterPipeline(output);
        return output;
    }

    public static class Builder {
        private DemoPipeline pipeline;
        private AuditRecorder auditRecorder;

        private Builder() {
        }

        public Builder pipeline(DemoPipeline pipeline) {
            this.pipeline = pipeline;
            return this;
        }

        public Builder auditRecorder(AuditRecorder auditRecorder) {
            this.auditRecorder = auditRecorder;
            return this;
        }

        public FlowContainer build() {
            return new FlowContainer(pipeline, auditRecorder);
        }
    }
}
