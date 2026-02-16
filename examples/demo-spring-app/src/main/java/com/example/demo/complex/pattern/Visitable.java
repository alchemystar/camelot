package com.example.demo.complex.pattern;

public interface Visitable<T extends Visitable<T>> {
    <R> R accept(Visitor<T, R> visitor);
}
