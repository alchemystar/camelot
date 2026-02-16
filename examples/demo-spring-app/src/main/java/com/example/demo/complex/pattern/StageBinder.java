package com.example.demo.complex.pattern;

import org.springframework.stereotype.Service;

@Service
public class StageBinder {

    public <C extends Visitable<C>, R> GenericPipeline<C, R> attachFirst(GenericPipeline<C, R> pipeline,
                                                                          FlowStage<C> stage) {
        return pipeline.addHandler(stage);
    }

    public <C extends Visitable<C>, R> GenericPipeline<C, R> attachNext(GenericPipeline<C, R> pipeline,
                                                                         FlowStage<C> stage) {
        return pipeline.addHandler(stage);
    }

    public <C extends Visitable<C>, R> GenericPipeline<C, R> attachLast(GenericPipeline<C, R> pipeline,
                                                                         FlowStage<C> stage) {
        return pipeline.addHandler(stage);
    }
}
