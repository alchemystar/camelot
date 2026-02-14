package com.example.demo.controller;

import com.example.demo.service.AuditService;
import com.example.demo.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private AuditService auditService;

    @GetMapping("/{id}")
    public String getUser(@PathVariable("id") String id) {
        auditService.recordRequest(id);
        String userPayload = userService.findUser(id);
        auditService.recordResponse(id, userPayload);
        return userPayload;
    }
}
