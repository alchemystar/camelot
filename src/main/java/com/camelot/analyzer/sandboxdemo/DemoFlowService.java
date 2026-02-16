package com.camelot.analyzer.sandboxdemo;

public class DemoFlowService extends AbstractFlowService {
    private PrefixStage prefixStage;
    private SuffixStage suffixStage;
    private InputSanitizer inputSanitizer;
    private AuditRecorder auditRecorder;

    @Override
    protected DemoPipelineBuilder appendCustom(DemoPipelineBuilder builder) {
        DemoPipelineBuilder current = builder;
        current = current.add(prefixStage);
        current = current.add(suffixStage);
        return current;
    }

    public String run(String request) {
        String sanitized = inputSanitizer.clean(request);

        DemoPipeline pipeline = buildPipeline();
        String result = FlowContainer.a()
                .pipeline(pipeline)
                .auditRecorder(auditRecorder)
                .build()
                .start(sanitized);

        auditRecorder.afterFinish(result);
        return result;
    }
}
