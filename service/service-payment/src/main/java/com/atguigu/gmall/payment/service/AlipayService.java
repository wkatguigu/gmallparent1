package com.atguigu.gmall.payment.service;

import com.alipay.api.AlipayApiException;

/**
 * @author mqx
 * @date 2020/5/8 10:16
 */

public interface AlipayService {
    // 支付接口 根据订单Id 来完成支付
    String createAliPay(Long orderId) throws AlipayApiException;

    // 根据orderId 退款。
    boolean refund(Long orderId);
    //关闭支付宝交易记录
    boolean closePay(Long orderId);
    //检查交易记录
    boolean checkPayment(Long orderId);



}
