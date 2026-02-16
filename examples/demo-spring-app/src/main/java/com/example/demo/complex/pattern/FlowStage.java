package com.example.demo.complex.pattern;

public interface FlowStage<C extends Visitable<C>> {
    C apply(C context);
}
