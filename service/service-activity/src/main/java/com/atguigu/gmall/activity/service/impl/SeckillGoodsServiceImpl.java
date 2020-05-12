package com.atguigu.gmall.activity.service.impl;


import com.alibaba.nacos.client.utils.StringUtils;
import com.atguigu.gmall.activity.mapper.SeckillGoodsMapper;
import com.atguigu.gmall.activity.service.SeckillGoodsService;
import com.atguigu.gmall.common.constant.RedisConst;
import com.atguigu.gmall.common.result.Result;
import com.atguigu.gmall.common.result.ResultCodeEnum;
import com.atguigu.gmall.common.util.CacheHelper;
import com.atguigu.gmall.common.util.MD5;
import com.atguigu.gmall.model.activity.OrderRecode;
import com.atguigu.gmall.model.activity.SeckillGoods;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 服务实现层
 *
 * @author Administrator
 */
@Service
@Transactional
public class SeckillGoodsServiceImpl implements SeckillGoodsService {

    @Autowired
    private SeckillGoodsMapper seckillGoodsMapper;

    @Autowired
    private RedisTemplate redisTemplate;


    /**
     * 查询全部秒杀的商品
     */
    @Override
    public List<SeckillGoods> findAll() {
        //每天夜晚扫描发送的消息，消费消息将数据放入缓存
        List<SeckillGoods> seckillGoodsList = redisTemplate.boundHashOps(RedisConst.SECKILL_GOODS).values();
        return seckillGoodsList;
    }

    /**
     * 根据ID获取实体
     *
     * @param id
     * @return
     */
    @Override
    public SeckillGoods getSeckillGoods(Long id) {
        return (SeckillGoods) redisTemplate.boundHashOps(RedisConst.SECKILL_GOODS).get(id.toString());
    }

    /***
     * 创建订单
     * @param skuId
     * @param userId
     */
    @Override
    public void seckillOrder(Long skuId, String userId) {
        //产品状态位， 1：可以秒杀 0：秒杀结束
        String state = (String) CacheHelper.get(skuId.toString());
        if("0".equals(state)) {
            //已售罄
            return;
        }
        //如和保证用户不能抢多次
        //如果用户第一次抢到了，那么就会将抢到的消息存储到缓存中
        //判断用户是否下单
        //如果执行成功返回true，说明第一次添加key
        boolean isExist = redisTemplate.opsForValue().setIfAbsent(RedisConst.SECKILL_USER + userId, skuId, RedisConst.SECKILL__TIMEOUT, TimeUnit.SECONDS);
        if (!isExist) {
            return;
        }
        //获取队列中的商品，如果能够获取，则商品存在，可以下单
        //添加商品时时leftpush 吐出来是 rightPop
        String goodsId = (String) redisTemplate.boundListOps(RedisConst.SECKILL_STOCK_PREFIX + skuId).rightPop();
        if (StringUtils.isEmpty(goodsId)) {
            //如果没有吐出来，那么就说明已经售罄了
            //商品售罄，更新状态位
            redisTemplate.convertAndSend("seckillpush", skuId+":0");
            //已售罄
            return;
        }

        //订单记录，做一个秒杀的订单表
        OrderRecode orderRecode = new OrderRecode();
        orderRecode.setUserId(userId);
        //通过skuId ，查询用户秒杀那个商品
        orderRecode.setSeckillGoods(this.getSeckillGoods(skuId));
        orderRecode.setNum(1);
        //生成下单码
        orderRecode.setOrderStr(MD5.encrypt(userId+skuId));

        //将用户秒杀的订单数据存入Reids
        redisTemplate.boundHashOps(RedisConst.SECKILL_ORDERS).put(orderRecode.getUserId(), orderRecode);
        //更新库存
        updateStockCount(orderRecode.getSeckillGoods().getSkuId());
    }
    /**
     * 更新库存
     * @param skuId
     */
    private void updateStockCount(Long skuId) {
        //更新库存，批量更新，用于页面显示，以实际扣减库存为准
        Long stockCount = redisTemplate.boundListOps(RedisConst.SECKILL_STOCK_PREFIX + skuId).size();
        //主要目的是为了不想频繁更新数据
        if (stockCount % 2 == 0) {
            //商品卖完,同步数据库
            SeckillGoods seckillGoods =getSeckillGoods(skuId);
            seckillGoods.setStockCount(stockCount.intValue());
            //更新数据库
            seckillGoodsMapper.updateById(seckillGoods);

            //更新缓存
            redisTemplate.boundHashOps(RedisConst.SECKILL_GOODS).put(skuId.toString(), seckillGoods);
        }
    }

    /***
     * 根据用户ID查看订单信息
     * @param userId
     * @return
     */
    @Override
    public Result checkOrder(Long skuId, String userId) {
        // 判断用户是否存在，用户不能购买两次
        //判断用户是否能够抢单
        boolean isExist =redisTemplate.hasKey(RedisConst.SECKILL_USER + userId);
        if (isExist) {
            //判断用户是否正在排队
            //判断用户key 中是否购买过当前商品
            boolean isHasKey = redisTemplate.boundHashOps(RedisConst.SECKILL_ORDERS).hasKey(userId);
            if (isHasKey) {
                //抢单成功，获取用户的订单对象
                OrderRecode orderRecode = (OrderRecode) redisTemplate.boundHashOps(RedisConst.SECKILL_ORDERS).get(userId);
                // 秒杀成功！返回数据
                return Result.build(orderRecode, ResultCodeEnum.SECKILL_SUCCESS);
            }
        }

        //判断用户是否下单
        boolean isExistOrder = redisTemplate.boundHashOps(RedisConst.SECKILL_ORDERS_USERS).hasKey(userId);
       //判断用户是否已经下过订单
        //如果返回true 说明执行成功
        if(isExistOrder) {
            //获取订单id
            String orderId = (String)redisTemplate.boundHashOps(RedisConst.SECKILL_ORDERS_USERS).get(userId);
            //应该是第一次下单成功
            return Result.build(orderId, ResultCodeEnum.SECKILL_ORDER_SUCCESS);
        }
        //判断状态位
        String state = (String) CacheHelper.get(skuId.toString());
       //status =1 表示能够抢单 如果是0 抢单失败 已经售罄
        if("0".equals(state)) {
            //已售罄 抢单失败
            return Result.build(null, ResultCodeEnum.SECKILL_FAIL);
        }

        //默认情况下，正在排队中
        return Result.build(null, ResultCodeEnum.SECKILL_RUN);
    }


}