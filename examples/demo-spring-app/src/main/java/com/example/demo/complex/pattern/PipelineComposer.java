package com.example.demo.complex.pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PipelineComposer {

    @Autowired
    private StageBinder stageBinder;

    public <C extends Visitable<C>, R> GenericPipeline<C, R> compose(GenericPipeline<C, R> pipeline,
                                                                      FlowStage<C> first,
                                                                      FlowStage<C> second,
                                                                      FlowStage<C> third) {
        GenericPipeline<C, R> current = stageBinder.attachFirst(pipeline, first);
        current = stageBinder.attachNext(current, second);
        return stageBinder.attachLast(current, third);
    }
}
