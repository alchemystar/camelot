package com.example.demo.complex.pattern;

import java.util.ArrayList;
import java.util.List;

public class GenericPipeline<C extends Visitable<C>, R> {
    private final List<FlowStage<C>> stages = new ArrayList<FlowStage<C>>();
    private Visitor<C, R> visitor;

    public GenericPipeline<C, R> addHandler(FlowStage<C> stage) {
        if (stage != null) {
            stages.add(stage);
        }
        return this;
    }

    public GenericPipeline<C, R> withVisitor(Visitor<C, R> visitor) {
        this.visitor = visitor;
        return this;
    }

    public R execute(C context) {
        C current = context;
        for (FlowStage<C> stage : stages) {
            current = stage.apply(current);
        }
        return current.accept(visitor);
    }
}
