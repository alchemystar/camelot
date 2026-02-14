package com.example.demo.service;

import com.example.demo.repo.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl implements UserService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RiskService riskService;

    @Autowired
    private UserEnricherService userEnricherService;

    @Autowired
    private AuditService auditService;

    @Override
    public String findUser(String id) {
        boolean risky = riskService.check(id);
        String rawUser = userRepository.findById(id);
        String enriched = userEnricherService.decorate(rawUser);
        auditService.recordLookup(id, risky, enriched);
        if (risky) {
            return "risky:" + enriched;
        }
        return enriched;
    }
}
