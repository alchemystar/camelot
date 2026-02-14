package com.example.demo.repo;

import org.springframework.stereotype.Repository;

@Repository
public class AccessLogRepository {

    public void write(String id, String message) {
        // no-op for demo
    }
}
