package com.example.demo.service;

import com.example.demo.dao.ExternalPaymentDao;
import com.example.demo.mapper.PaymentMapper;
import com.example.demo.thrift.OrderQueryClient;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {
    private final ExternalPaymentDao paymentDao;
    private final PaymentMapper paymentMapper;
    private final OrderQueryClient orderQueryClient;

    public PaymentService(ExternalPaymentDao paymentDao,
                          PaymentMapper paymentMapper,
                          OrderQueryClient orderQueryClient) {
        this.paymentDao = paymentDao;
        this.paymentMapper = paymentMapper;
        this.orderQueryClient = orderQueryClient;
    }

    public String pay(String orderNo) {
        String daoResult = paymentDao.save(orderNo);
        String mapperResult = paymentMapper.queryStatus(orderNo);
        String thriftResult = orderQueryClient.query(orderNo);
        return "pay(" + orderNo + ") dao=" + daoResult + ", mapper=" + mapperResult + ", thrift=" + thriftResult;
    }
}
