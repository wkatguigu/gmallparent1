package com.atguigu.gmall.order.receiver;

import com.alibaba.fastjson.JSON;
import com.alibaba.nacos.client.utils.StringUtils;
import com.atguigu.gmall.common.constant.MqConst;
import com.atguigu.gmall.common.service.RabbitService;
import com.atguigu.gmall.model.enums.PaymentStatus;
import com.atguigu.gmall.model.enums.ProcessStatus;
import com.atguigu.gmall.model.order.OrderInfo;
import com.atguigu.gmall.model.payment.PaymentInfo;
import com.atguigu.gmall.order.service.OrderService;
import com.atguigu.gmall.payment.client.PaymentFeignClient;
import com.rabbitmq.client.Channel;
import lombok.SneakyThrows;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * @author mqx
 * 取消订单
 * @date 2020/5/6 14:27
 */
@Component
public class OrderReceiver {

    @Autowired
    private OrderService orderService;
    @Autowired
    private PaymentFeignClient paymentFeignClient;
    @Autowired
    private  RabbitService rabbitService;

    // 获取队列中的消息
    // 发送消息的时候，发送的是订单Id，
    @SneakyThrows
    @RabbitListener(queues = MqConst.QUEUE_ORDER_CANCEL)
    public void orderCancel(Long orderId , Message message, Channel channel){
        if (null!=orderId){
            // 根据订单Id 查询订单记录
            OrderInfo orderInfo = orderService.getById(orderId);

            if (null!= orderInfo&& orderInfo.getOrderStatus().equals(ProcessStatus.UNPAID.getOrderStatus().name())){
                //先关闭paymentInfo 后关闭orderInfo 因为支付成功后 异步回调先修改 paymentInfo
                PaymentInfo paymentInfo = paymentFeignClient.getPaymentInfo(orderInfo.getOutTradeNo());
                // 先检查支付交易记录
                if (null!=paymentInfo && paymentInfo.getPaymentStatus().equals(PaymentStatus.UNPAID.name())){
                    // 关闭支付宝,先判断是否有交易记录。如果有交易记录，没有付款成功那么才能关闭成功！否则关闭失败。
                    Boolean flag = paymentFeignClient.checkPayment(orderId);
                    // 如果返回true ，则说明有交易记录
                    if (flag){
                        // 有交易记录，那么才调用关闭支付接口
                        Boolean result = paymentFeignClient.closePay(orderId);
                        //判断是否关闭成功
                        // 如果关闭成功，则说明用户没有付款，
                        if (result){
                            // 关闭交易记录状态，更改订单状态。
                            orderService.execExpiredOrder(orderId,"2");
                        }else {
                            // 说明没有关闭成功，那么一定是用户付款了。
                            // 发送支付成功的消息队列。
                            rabbitService.sendMessage(MqConst.EXCHANGE_DIRECT_PAYMENT_PAY,MqConst.ROUTING_PAYMENT_PAY,orderId);
                        }
                    }else {
                        // 用户生成了二维码，但是没有扫描。
                        orderService.execExpiredOrder(orderId,"2");
                    }
                } else{
              /*
               支付交易记录中为空，那么说明用户根本没有生成付款码。没有生成付款码，那么可能下单了。
               所以关闭订单的状态
               */
                    if (orderInfo.getOrderStatus().equals(ProcessStatus.UNPAID.getOrderStatus().name())){
                        orderService.execExpiredOrder(orderId,"1");
                    }
                }
            }
        }
        // 手动ack
        channel.basicAck(message.getMessageProperties().getDeliveryTag(),false);
    }
        //监听消息，然后更改订单
        @RabbitListener(bindings = @QueueBinding(
                value = @Queue(value = MqConst.QUEUE_PAYMENT_PAY),
                exchange = @Exchange(value = MqConst.EXCHANGE_DIRECT_PAYMENT_PAY),
                key = {MqConst.ROUTING_PAYMENT_PAY})
                )
    public void getMsg(Long orderId,Message message,Channel channel) throws IOException {
        //判断 orderID 不能巍峨空
            if (null!=orderId){
                //判断支付状态是未付款
                OrderInfo orderInfo = orderService.getById(orderId);
                if (null!=orderId){
                    if (orderInfo.getOrderStatus().equals(ProcessStatus.UNPAID.getOrderStatus().name())){
                        //更新订单的状态
                    orderService.updateOrderStatus(orderId,ProcessStatus.PAID);
                    //发送消息给库存
                    orderService.sendOrderStatus(orderId);
                    }
                }
                channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);

            }

    }
    //监听库存系统的
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConst.QUEUE_WARE_ORDER),
            exchange=@Exchange(value = MqConst.EXCHANGE_DIRECT_WARE_ORDER),
            key = {MqConst.ROUTING_WARE_ORDER}
    ))
    public void updateOrderStatus(String msgJson,Message message,Channel channel){
        if (!StringUtils.isEmpty(msgJson)){
            Map map = JSON.parseObject(msgJson, Map.class);
            String orderId = (String) map.get("orderId");
            String status = (String) map.get("status");
            //判断见库存是否成功
            if ("DEDUCTED".equals(status)){
                //见库存成功，将订单的状态等待发货
                orderService.updateOrderStatus(Long.parseLong(orderId),ProcessStatus.WAITING_DELEVER);
            }else{
                //减库存失败，发生超买
                //1 调用其他仓库的库存，补货。2 找人工客服介入 与客户进行沟通
                orderService.updateOrderStatus(Long.parseLong(orderId),ProcessStatus.STOCK_EXCEPTION);
            }
        }
    }

}
