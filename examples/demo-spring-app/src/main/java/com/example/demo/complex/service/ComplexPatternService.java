package com.example.demo.complex.service;

import com.example.demo.complex.model.ComplexFlowContext;
import com.example.demo.complex.observer.AuditObserver;
import com.example.demo.complex.observer.MetricObserver;
import com.example.demo.complex.pattern.AbstractTemplateFlow;
import com.example.demo.complex.pattern.FlowObserverBus;
import com.example.demo.complex.pattern.GenericPipeline;
import com.example.demo.complex.pattern.ObserverAssembler;
import com.example.demo.complex.pattern.PipelineComposer;
import com.example.demo.complex.stage.EnrichStage;
import com.example.demo.complex.stage.NormalizeStage;
import com.example.demo.complex.stage.RiskStage;
import com.example.demo.complex.visitor.SummaryVisitor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ComplexPatternService
        extends AbstractTemplateFlow<String, ComplexFlowContext<String, String>, String> {

    @Autowired
    private PipelineComposer pipelineComposer;

    @Autowired
    private ObserverAssembler observerAssembler;

    @Autowired
    private NormalizeStage normalizeStage;

    @Autowired
    private EnrichStage enrichStage;

    @Autowired
    private RiskStage riskStage;

    @Autowired
    private AuditObserver auditObserver;

    @Autowired
    private MetricObserver metricObserver;

    @Autowired
    private SummaryVisitor summaryVisitor;

    @Override
    protected ComplexFlowContext<String, String> createContext(String key) {
        return ComplexFlowContext.of(key, key);
    }

    @Override
    protected FlowObserverBus<ComplexFlowContext<String, String>, String> createObserverBus(
            ComplexFlowContext<String, String> context) {
        return observerAssembler.assemble(auditObserver, metricObserver);
    }

    @Override
    protected GenericPipeline<ComplexFlowContext<String, String>, String> buildPipeline(
            ComplexFlowContext<String, String> context) {
        GenericPipeline<ComplexFlowContext<String, String>, String> pipeline =
                new GenericPipeline<ComplexFlowContext<String, String>, String>();
        GenericPipeline<ComplexFlowContext<String, String>, String> assembled =
                pipelineComposer.compose(pipeline, normalizeStage, enrichStage, riskStage);
        return assembled.withVisitor(summaryVisitor);
    }

    @Override
    protected String finalizeResult(ComplexFlowContext<String, String> context, String result) {
        context.addTrace("template-finalize");
        return result;
    }

    public String run(String requestId) {
        return process(requestId);
    }

    public GenericPipeline<ComplexFlowContext<String, String>, String> buildFlow(String requestId) {
        ComplexFlowContext<String, String> seed = createContext(requestId);
        return buildPipeline(seed);
    }

    public String runByChain(String requestId) {
        ComplexFlowContext<String, String> context = createContext(requestId);
        return buildFlow(requestId).execute(context);
    }
}
