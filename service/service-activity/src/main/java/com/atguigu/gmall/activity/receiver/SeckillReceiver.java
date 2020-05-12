package com.atguigu.gmall.activity.receiver;


import com.alibaba.nacos.client.utils.StringUtils;
import com.atguigu.gmall.activity.mapper.SeckillGoodsMapper;
import com.atguigu.gmall.activity.service.SeckillGoodsService;
import com.atguigu.gmall.common.constant.MqConst;
import com.atguigu.gmall.common.constant.RedisConst;
import com.atguigu.gmall.common.util.DateUtil;
import com.atguigu.gmall.model.activity.SeckillGoods;
import com.atguigu.gmall.model.activity.UserRecode;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;


import java.io.IOException;
import java.util.Date;
import java.util.List;

@Component
public class SeckillReceiver {
    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private SeckillGoodsMapper seckillGoodsMapper;
    @Autowired
    private SeckillGoodsService seckillGoodsService;

    //编写
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConst.QUEUE_TASK_1),
            exchange = @Exchange(value = MqConst.EXCHANGE_DIRECT_TASK),
            key = {MqConst.ROUTING_TASK_1}
    ))
    public void importItemToRedis(Message message, Channel channel) throws IOException {
        QueryWrapper<SeckillGoods> queryWrapper = new QueryWrapper<>();
        // 查询审核状态1 并且库存数量大于0，当天的商品
        queryWrapper.eq("status",1).gt("stock_count",0);
        queryWrapper.eq("DATE_FORMAT(start_time,'%Y-%m-%d')", DateUtil.formatDate(new Date()));
        List<SeckillGoods> list = seckillGoodsMapper.selectList(queryWrapper);

        // 将集合数据放入缓存中
        if (list!=null && list.size()>0){
            for (SeckillGoods seckillGoods : list) {
                // 使用hash 数据类型保存商品
                                // key = seckill:goods field = skuId
                // 判断缓存中是否有当前key
                Boolean flag = redisTemplate.boundHashOps(RedisConst.SECKILL_GOODS).hasKey(seckillGoods.getSkuId().toString());
                if (flag){
                    // 当前商品已经在缓存中有了！ 所以不需要在放入缓存！
                    continue;
                }
                //缓存中没有数据 秒杀商品放入缓存
                // 商品id为field ，对象为value 放入缓存  key = seckill:goods field = skuId value=商品字符串
                redisTemplate.boundHashOps(RedisConst.SECKILL_GOODS).put(seckillGoods.getSkuId().toString(), seckillGoods);
                // hset(seckill:goods,1,{" skuNum 10"})
                // hset(seckill:goods,2,{" skuNum 10"})
                //根据每一个商品的数量把商品按队列的形式放进redis中
                //分析商品数量如存储，如何防止库存超买
                for (Integer i = 0; i < seckillGoods.getStockCount(); i++) {
                    // key = seckill:stock:skuId
                    // lpush key value
                    redisTemplate.boundListOps(RedisConst.SECKILL_STOCK_PREFIX+seckillGoods.getSkuId()).leftPush(seckillGoods.getSkuId().toString());
                }
                //将所有的商品状态为初始化为1 状态位只有为1 的时候,那么这个商品才可以秒，为0的时候不能秒
                redisTemplate.convertAndSend("seckillpush",seckillGoods.getSkuId()+":1");
            }

            // 手动确认接收消息成功
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        }
    }

    /**
     * 秒杀用户加入队列
     *
     * @param message
     * @param channel
     * @throws IOException
     * 监听用户发送过来的消息
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConst.QUEUE_SECKILL_USER),
            exchange = @Exchange(value = MqConst.EXCHANGE_DIRECT_SECKILL_USER),
            key = {MqConst.ROUTING_SECKILL_USER}
    ))
    public void seckillGoods(UserRecode userRecode, Message message, Channel channel) throws IOException {
       //判断用户信息不能为空
        if (null != userRecode) {
            //Log.info("paySuccess:"+ JSONObject.toJSONString(userRecode));
            //预下单处理
            seckillGoodsService.seckillOrder(userRecode.getSkuId(), userRecode.getUserId());

            //确认收到消息
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        }
    }
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConst.QUEUE_TASK_18),
            exchange = @Exchange(value = MqConst.EXCHANGE_DIRECT_TASK),
            key = {MqConst.ROUTING_TASK_18}
    ))
    public void delseckillGoods( Message message, Channel channel) throws IOException {
        //删除操作
        //查询结束的商品秒杀
        QueryWrapper<SeckillGoods> seckillGoodsQueryWrapper = new QueryWrapper<>();
        //结束时间
        seckillGoodsQueryWrapper.eq("status",1).le("end_time",new Date());
        List<SeckillGoods> seckillGoodsList = seckillGoodsMapper.selectList(seckillGoodsQueryWrapper);

        //删除缓存数据
        for (SeckillGoods seckillGoods : seckillGoodsList) {
            //删除缓存的秒杀商品的数量
            redisTemplate.delete(RedisConst.SECKILL_STOCK_PREFIX+seckillGoods.getSkuId());
        }
        //继续删除
        redisTemplate.delete(RedisConst.SECKILL_ORDERS);
        redisTemplate.delete(RedisConst.SECKILL_ORDERS_USERS);
        //变更数据库的状态
        //status=1 ,status=2
        SeckillGoods seckillGoods = new SeckillGoods();
        seckillGoods.setStatus("2");
        seckillGoodsMapper.update(seckillGoods,seckillGoodsQueryWrapper);
         //手动确认消息已经被消费
        channel.basicAck(message.getMessageProperties().getDeliveryTag(),false);
    }
}