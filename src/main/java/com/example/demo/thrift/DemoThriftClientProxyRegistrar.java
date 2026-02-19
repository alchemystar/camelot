package com.example.demo.thrift;

import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

public class DemoThriftClientProxyRegistrar implements ImportBeanDefinitionRegistrar {

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata,
                                        BeanDefinitionRegistry registry) {
        RootBeanDefinition definition = new RootBeanDefinition(DemoThriftClientProxy.class);
        definition.getPropertyValues().add("serviceInterface",
                new TypedStringValue("interface com.example.demo.thrift.OrderQueryClient"));
        registry.registerBeanDefinition("orderQueryClient", definition);
    }
}
