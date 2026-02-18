package com.example.demo;

import com.example.demo.service.PaymentService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class DemoStartupRunner implements ApplicationRunner {

    private final PaymentService paymentService;

    public DemoStartupRunner(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Override
    public void run(ApplicationArguments args) {
        String result = paymentService.pay("ORDER-1001");
        System.out.println("[DEMO] runner finished: " + result);
    }
}
