package com.example.demo.complex.pattern;

public interface Visitor<T extends Visitable<T>, R> {
    R visit(T target);
}
