package com.example.demo.dao;

import org.springframework.stereotype.Repository;

@Repository
public class ExternalPaymentDao {

    public String save(String orderNo) {
        throw new IllegalStateException("Real DAO should not be called in runtime bootstrap");
    }
}
