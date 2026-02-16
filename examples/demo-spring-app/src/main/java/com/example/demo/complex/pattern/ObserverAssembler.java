package com.example.demo.complex.pattern;

import org.springframework.stereotype.Service;

@Service
public class ObserverAssembler {

    public <C extends Visitable<C>, R> FlowObserverBus<C, R> assemble(FlowObserver<C, R> first,
                                                                       FlowObserver<C, R> second) {
        FlowObserverBus<C, R> bus = new FlowObserverBus<C, R>();
        bus.addObserver(first);
        bus.addObserver(second);
        return bus;
    }
}
