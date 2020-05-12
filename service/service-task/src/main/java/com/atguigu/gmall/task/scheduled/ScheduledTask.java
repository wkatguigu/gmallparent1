package com.atguigu.gmall.task.scheduled;

import com.atguigu.gmall.common.constant.MqConst;
import com.atguigu.gmall.common.service.RabbitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;




@Component
@EnableScheduling //表示开启定时任务
@Slf4j
public class ScheduledTask {
    @Autowired
    private RabbitService rabbitService;
 /**
 * @Scheduled(cron = "0 0 1 * * ?")每天凌晨1点执行
 */
  //@Scheduled(cron = "0/30 * * * * ?")每个30秒出发当前任务，定义任务开启时间 凌晨一点钟
 @Scheduled(cron = "0/30 * * * * ?")
 //发送的内容是空。处理消息的时候扫描秒杀商品！
  public void task1() {
    rabbitService.sendMessage(MqConst.EXCHANGE_DIRECT_TASK,  MqConst.ROUTING_TASK_1, "");
 }
 //每天晚上18点删除
    @Scheduled(cron = "* * 18 * * ?")
    //发送的内容是空。处理消息的时候扫描秒杀商品！
    public void taskDelRedis() {
        rabbitService.sendMessage(MqConst.EXCHANGE_DIRECT_TASK,  MqConst.ROUTING_TASK_1, "");
    }

}