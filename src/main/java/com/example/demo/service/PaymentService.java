package com.example.demo.service;

import com.example.demo.dao.ExternalPaymentDao;
import com.example.demo.mapper.PaymentMapper;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {
    private final ExternalPaymentDao paymentDao;
    private final PaymentMapper paymentMapper;

    public PaymentService(ExternalPaymentDao paymentDao, PaymentMapper paymentMapper) {
        this.paymentDao = paymentDao;
        this.paymentMapper = paymentMapper;
    }

    public String pay(String orderNo) {
        String daoResult = paymentDao.save(orderNo);
        String mapperResult = paymentMapper.queryStatus(orderNo);
        return "pay(" + orderNo + ") dao=" + daoResult + ", mapper=" + mapperResult;
    }
}
