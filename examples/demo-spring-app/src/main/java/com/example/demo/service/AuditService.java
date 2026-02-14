package com.example.demo.service;

import com.example.demo.repo.AccessLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    @Autowired
    private AccessLogRepository accessLogRepository;

    @Autowired
    private RuntimeService runtimeService;

    public void recordRequest(String id) {
        accessLogRepository.write(id, "request");
    }

    public void recordResponse(String id, String payload) {
        accessLogRepository.write(id, "response:" + payload);
    }

    public void recordLookup(String id, boolean risky, String payload) {
        String state = risky ? "HIGH" : "NORMAL";
        accessLogRepository.write(id, "lookup:" + state);
        runtimeService.start(id);
        accessLogRepository.write(id, "payload:" + payload);
    }
}
