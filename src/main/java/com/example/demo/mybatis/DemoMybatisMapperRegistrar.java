package com.example.demo.mybatis;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

public class DemoMybatisMapperRegistrar implements ImportBeanDefinitionRegistrar {

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata,
                                        BeanDefinitionRegistry registry) {
        RootBeanDefinition scannerMetadata = new RootBeanDefinition(DemoMapperScannerMetadata.class);
        scannerMetadata.getPropertyValues().add("basePackage", "com.example.demo.mybatis");
        scannerMetadata.setLazyInit(true);
        registry.registerBeanDefinition("demoMapperScannerConfigurer", scannerMetadata);

        RootBeanDefinition mapperFactoryBean = new RootBeanDefinition();
        mapperFactoryBean.setBeanClassName("org.mybatis.spring.mapper.MapperFactoryBean");
        mapperFactoryBean.getPropertyValues().add("mapperInterface", OrderStatusMapper.class);
        mapperFactoryBean.setLazyInit(true);
        registry.registerBeanDefinition("orderStatusMapper", mapperFactoryBean);
    }
}
