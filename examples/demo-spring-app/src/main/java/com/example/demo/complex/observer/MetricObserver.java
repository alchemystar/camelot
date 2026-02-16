package com.example.demo.complex.observer;

import com.example.demo.complex.model.ComplexFlowContext;
import com.example.demo.complex.pattern.FlowObserver;
import org.springframework.stereotype.Service;

@Service
public class MetricObserver implements FlowObserver<ComplexFlowContext<String, String>, String> {

    @Override
    public void onStart(ComplexFlowContext<String, String> context) {
        context.putAttribute("metricStart", Long.valueOf(System.currentTimeMillis()));
        context.addTrace("observer-metric-start");
    }

    @Override
    public void onFinish(ComplexFlowContext<String, String> context, String result) {
        Long start = context.getAttribute("metricStart", Long.class);
        long elapsed = 0L;
        if (start != null) {
            elapsed = System.currentTimeMillis() - start.longValue();
        }
        context.putAttribute("metricElapsed", Long.valueOf(elapsed));
        context.addTrace("observer-metric-finish");
    }
}
