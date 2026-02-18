package com.example.demo.mapper;

import org.springframework.stereotype.Repository;

@Repository
public class PaymentMapper {

    public String queryStatus(String orderNo) {
        throw new IllegalStateException("Real mapper should not be called in runtime bootstrap");
    }
}
