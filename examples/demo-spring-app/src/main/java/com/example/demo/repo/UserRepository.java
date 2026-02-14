package com.example.demo.repo;

import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    public String findById(String id) {
        return "user:" + id;
    }
}
