package com.example.demo.service;

public abstract class AbstractPayApp<T extends AbstractPayApp<T>> {

    @SuppressWarnings("unchecked")
    public T build(String request) {
        return (T) this;
    }
}
