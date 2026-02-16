package com.example.demo.interfacepipe.core;

public interface Pipeline<Request, Response, Ctx> {
    Response execute(Request request, Ctx ctx);
}
