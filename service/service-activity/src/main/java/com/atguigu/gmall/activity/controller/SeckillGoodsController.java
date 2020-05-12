package com.atguigu.gmall.activity.controller;


import com.alibaba.nacos.client.utils.StringUtils;
import com.atguigu.gmall.activity.service.SeckillGoodsService;
import com.atguigu.gmall.common.constant.MqConst;
import com.atguigu.gmall.common.constant.RedisConst;
import com.atguigu.gmall.common.result.Result;
import com.atguigu.gmall.common.result.ResultCodeEnum;
import com.atguigu.gmall.common.service.RabbitService;
import com.atguigu.gmall.common.util.AuthContextHolder;
import com.atguigu.gmall.common.util.CacheHelper;
import com.atguigu.gmall.common.util.DateUtil;
import com.atguigu.gmall.common.util.MD5;
import com.atguigu.gmall.model.activity.OrderRecode;
import com.atguigu.gmall.model.activity.SeckillGoods;
import com.atguigu.gmall.model.activity.UserRecode;
import com.atguigu.gmall.model.order.OrderDetail;
import com.atguigu.gmall.model.order.OrderInfo;
import com.atguigu.gmall.model.user.UserAddress;
import com.atguigu.gmall.order.client.OrderFeignClient;
import com.atguigu.gmall.product.client.ProductFeignClient;
import com.atguigu.gmall.user.client.UserFeignClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.BoundHashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

/**
 * controller
 *
 */
@RestController
@RequestMapping("/api/activity/seckill")
public class SeckillGoodsController {

    @Autowired
    private SeckillGoodsService seckillGoodsService;

    @Autowired
    private UserFeignClient userFeignClient;

    @Autowired
    private ProductFeignClient productFeignClient;
    @Autowired
    private RabbitService rabbitService;
    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private OrderFeignClient orderFeignClient;

    /**
     * 查询到所有的秒杀商品数据
     *
     * @return
     */
    @GetMapping("/findAll")
    public Result findAll() {
        return Result.ok(seckillGoodsService.findAll());
    }

    /**
     * 获取秒杀商品详情数据
     *
     * @param skuId
     * @return
     */
    @GetMapping("/getSeckillGoods/{skuId}")
    public Result getSeckillGoods(@PathVariable("skuId") Long skuId) {
        return Result.ok(seckillGoodsService.getSeckillGoods(skuId));
    }
    /**
     * 获取下单码
     * @param skuId
     * @return
     */
    @GetMapping("auth/getSeckillSkuIdStr/{skuId}")
    public Result getSeckillSkuIdStr(@PathVariable("skuId") Long skuId, HttpServletRequest request) {
        //将用户id 进行MD5加密后就是一个下单码
        String userId = AuthContextHolder.getUserId(request);
        //用户要秒杀的商品
        SeckillGoods seckillGoods = seckillGoodsService.getSeckillGoods(skuId);
        //判断
        if (null != seckillGoods) {
            //获取下单码，在商品的秒杀时间范围内才能获取，活动开始之后，结束之前
            Date curTime = new Date();//获取当前的系统时间
            if (DateUtil.dateCompare(seckillGoods.getStartTime(), curTime) && DateUtil.dateCompare(curTime, seckillGoods.getEndTime())) {
                //可以动态生成，放在redis缓存
                //skuIdStr 是下单码
                //用户要想秒杀商品必须先获取到下单码，主要是为了防止用户直接进入到下单的控制器进行秒杀
                //符合条件 才能生成下单码
                String skuIdStr = MD5.encrypt(userId);
                //保存 skuIdStr 返回给页面使用
                return Result.ok(skuIdStr);
            }
        }
        return Result.fail().message("获取下单码失败");
    }

    /**
     * 根据用户和商品ID实现秒杀下单
     *
     * @param skuId
     * @return
     */
    @PostMapping("auth/seckillOrder/{skuId}")
    public Result seckillOrder(@PathVariable("skuId") Long skuId, HttpServletRequest request) throws Exception {
        //校验下单码（抢购码规则可以自定义）
        String userId = AuthContextHolder.getUserId(request);
        //根据后台规则生成的下单码
        String skuIdStr = request.getParameter("skuIdStr");
        if (!skuIdStr.equals(MD5.encrypt(userId))) {
            //请求不合法
            return Result.build(null, ResultCodeEnum.SECKILL_ILLEGAL);
        }

        //产品标识， 1：可以秒杀 0：秒杀结束
        //获取状态位
        String state = (String) CacheHelper.get(skuId.toString());
        //判断状态位
        if (StringUtils.isEmpty(state)) {
            //请求不合法
            return Result.build(null, ResultCodeEnum.SECKILL_ILLEGAL);
        }
        if ("1".equals(state)) {
            //用户记录
            UserRecode userRecode = new UserRecode();
            userRecode.setUserId(userId);
            userRecode.setSkuId(skuId);

            rabbitService.sendMessage(MqConst.EXCHANGE_DIRECT_SECKILL_USER, MqConst.ROUTING_SECKILL_USER, userRecode);
        } else {
            //已售罄
            return Result.build(null, ResultCodeEnum.SECKILL_FINISH);
        }
        return Result.ok();
    }
    /**
     * 查询秒杀状态
     * @return
     */
    @GetMapping(value = "auth/checkOrder/{skuId}")
    public Result checkOrder(@PathVariable("skuId") Long skuId, HttpServletRequest request) {
        //获取用户id
        String userId = AuthContextHolder.getUserId(request);
        //调用服务层方法
        return seckillGoodsService.checkOrder(skuId, userId);
    }
    //准备给下订单页面提供数据支持的
    @GetMapping("auth/trade")
    public Result trade(HttpServletRequest request){
        //显示收货人地址，送货清单，总金额等
        //获取用户id
        String userId = AuthContextHolder.getUserId(request);
        //获取收货地址列表
        List<UserAddress> userAddressList = userFeignClient.findUserAddressListByUserId(userId);
        //获取用户购买的商品
        OrderRecode orderRecode  = (OrderRecode) redisTemplate.boundHashOps(RedisConst.SECKILL_ORDERS).get(userId);
        if (null==orderRecode){
            return Result.fail().message("非法操作");
        }
        //获取用户购买的商品
        SeckillGoods seckillGoods = orderRecode.getSeckillGoods();
        //声明一个集合来存储订单明细
        ArrayList<OrderDetail> detailArrayList = new ArrayList<>();
        OrderDetail orderDetail = new OrderDetail();
        //给订单明细赋值
        orderDetail.setSkuId(seckillGoods.getSkuId());
        orderDetail.setSkuName(seckillGoods.getSkuName());
        orderDetail.setImgUrl(seckillGoods.getSkuDefaultImg());
        orderDetail.setSkuNum(orderRecode.getNum());//在页面显示用户秒杀的商品
        orderDetail.setOrderPrice(seckillGoods.getCostPrice());
        //还需要见数据保存到数据库
        detailArrayList.add(orderDetail);
        //计算总金额
        OrderInfo orderInfo = new OrderInfo();
        orderInfo.setOrderDetailList(detailArrayList);
        orderInfo.sumTotalAmount();
        HashMap<String, Object> map = new HashMap<>();
        map.put("detailArrayList",detailArrayList);
        map.put("userAddressList",userAddressList);
        map.put("totalNum",orderRecode.getNum());
        map.put("totalAmount",orderInfo.getTotalAmount());
        
        return Result.ok(map);
    }
    /**
     * 秒杀提交订单
     *
     * @param orderInfo
     * @return
     */
    @PostMapping("auth/submitOrder")
    public Result submitOrder(@RequestBody OrderInfo orderInfo, HttpServletRequest request) {
        String userId = AuthContextHolder.getUserId(request);

        OrderRecode orderRecode = (OrderRecode) redisTemplate.boundHashOps(RedisConst.SECKILL_ORDERS).get(userId);
        if (null == orderRecode) {
            return Result.fail().message("非法操作");
        }

        orderInfo.setUserId(Long.parseLong(userId));

        Long orderId = orderFeignClient.submitOrder(orderInfo);
        if (null == orderId) {
            return Result.fail().message("下单失败，请重新操作");
        }

        //删除下单信息
        redisTemplate.boundHashOps(RedisConst.SECKILL_ORDERS).delete(userId);
        //下单记录
        redisTemplate.boundHashOps(RedisConst.SECKILL_ORDERS_USERS).put(userId, orderId.toString());

        return Result.ok(orderId);
    }
}