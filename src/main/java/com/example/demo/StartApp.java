package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import com.example.demo.mybatis.DemoMybatisMapperRegistrar;
import com.example.demo.thrift.DemoThriftClientProxyRegistrar;

@SpringBootApplication(scanBasePackages = "com.example.demo")
@Import({DemoThriftClientProxyRegistrar.class, DemoMybatisMapperRegistrar.class})
public class StartApp {
    public static void main(String[] args) {
        System.out.println("hahaha");
        SpringApplication.run(StartApp.class, args);
    }
}
