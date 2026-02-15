package com.example.demo.pipeline;

import com.example.demo.repo.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LoadUserStage implements PipelineStage {

    @Autowired
    private UserRepository userRepository;

    @Override
    public String apply(String input) {
        return userRepository.findById(input);
    }
}
