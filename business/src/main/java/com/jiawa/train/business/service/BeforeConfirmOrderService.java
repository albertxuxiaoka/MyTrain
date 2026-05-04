package com.jiawa.train.business.service;

import cn.hutool.core.date.DateTime;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.jiawa.train.business.domain.ConfirmOrder;
import com.jiawa.train.business.dto.ConfirmOrderMQDto;
import com.jiawa.train.business.enums.ConfirmOrderStatusEnum;
import com.jiawa.train.business.mapper.ConfirmOrderMapper;
import com.jiawa.train.business.req.ConfirmOrderDoReq;
import com.jiawa.train.business.req.ConfirmOrderTicketReq;
import com.jiawa.train.common.context.LoginMemberContext;
import com.jiawa.train.common.exception.BusinessException;
import com.jiawa.train.common.exception.BusinessExceptionEnum;
import com.jiawa.train.common.util.SnowUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BeforeConfirmOrderService {

    private static final Logger LOG = LoggerFactory.getLogger(BeforeConfirmOrderService.class);

    @Resource
    private ConfirmOrderMapper confirmOrderMapper;

    @Autowired
    private SkTokenService skTokenService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    // @Resource
    // public RocketMQTemplate rocket
    // public RocketMQTemplate rocketMQTemplate;

    // 后置流程由 RabbitMQ 消费者触发

    private static final String REDIS_KEY_SEAT_SELL_PRE = "DAILY_TRAIN_TICKET_SELL";

    private static final String REDIS_KEY_TRAIN_CARRIAGE_COUNT = "DAILY_TRAIN_CARRIAGE_COUNT";

    private static final String REDIS_KEY_CARRIAGE_SEAT_COUNT_PRE = "DAILY_TRAIN_CARRIAGE_SEAT_COUNT";

    private static final String REDIS_KEY_STATION_INDEX_PRE = "DAILY_TRAIN_STATION_INDEX";

    @SentinelResource(value = "beforeDoConfirm", blockHandler = "beforeDoConfirmBlock")
    public Long beforeDoConfirm(ConfirmOrderDoReq req) {
        req.setMemberId(LoginMemberContext.getId());

        // 一个请求只允许购买一张票（一个乘客），且不可选座
        List<ConfirmOrderTicketReq> tickets = req.getTickets();
        if (tickets == null || tickets.size() != 1) {
            throw new BusinessException(BusinessExceptionEnum.CONFIRM_ORDER_EXCEPTION);
        }
        if (tickets.get(0) != null && StrUtil.isNotBlank(tickets.get(0).getSeat())) {
            throw new BusinessException(BusinessExceptionEnum.CONFIRM_ORDER_EXCEPTION);
        }

        // 校验令牌余量
        boolean validSkToken = skTokenService.validSkToken(req.getDate(), req.getTrainCode(), LoginMemberContext.getId());
        if (validSkToken) {
            LOG.info("令牌校验通过");
        } else {
            LOG.info("令牌校验不通过");
            throw new BusinessException(BusinessExceptionEnum.CONFIRM_ORDER_SK_TOKEN_FAIL);
        }

        // 令牌校验通过后：先抢座（Lua 原子选座+占座），成功后再落库确认订单
        // 从 Redis 一次取出 start/end 的站序（不查库）
        Integer startIndex = null;
        Integer endIndex = null;
        {
            String dateStr = cn.hutool.core.date.DateUtil.formatDate(req.getDate());
            String key = REDIS_KEY_STATION_INDEX_PRE + "-" + dateStr + "-" + req.getTrainCode();
            List<Object> indices = redisTemplate.opsForHash().multiGet(key, Arrays.asList(req.getStart(), req.getEnd()));
            if (indices != null && indices.size() == 2) {
                startIndex = indices.get(0) == null ? null : Integer.valueOf(indices.get(0).toString());
                endIndex = indices.get(1) == null ? null : Integer.valueOf(indices.get(1).toString());
            }
        }
        if (startIndex == null || endIndex == null) {
            throw new BusinessException(BusinessExceptionEnum.CONFIRM_ORDER_EXCEPTION);
        }

        ChosenSeat chosenSeat = chooseAndOccupyOneSeatByLua(
                req.getDate(),
                req.getTrainCode(),
                tickets.get(0).getSeatTypeCode(),
                startIndex,
                endIndex
        );
        if (chosenSeat == null) {
            throw new BusinessException(BusinessExceptionEnum.CONFIRM_ORDER_TICKET_COUNT_ERROR);
        }

        // 订单落库时只写“车厢内座位序号”（不写具体排/列）
        tickets.get(0).setCarriageIndex(chosenSeat.carriageIndex());
        tickets.get(0).setCarriageSeatIndex(chosenSeat.carriageSeatIndex());

        // 保存确认订单表（INIT）
        DateTime now = DateTime.now();
        ConfirmOrder confirmOrder = new ConfirmOrder();
        confirmOrder.setId(SnowUtil.getSnowflakeNextId());
        confirmOrder.setCreateTime(now);
        confirmOrder.setUpdateTime(now);
        confirmOrder.setMemberId(req.getMemberId());
        confirmOrder.setDate(req.getDate());
        confirmOrder.setTrainCode(req.getTrainCode());
        confirmOrder.setStart(req.getStart());
        confirmOrder.setEnd(req.getEnd());
        confirmOrder.setDailyTrainTicketId(req.getDailyTrainTicketId());
        confirmOrder.setStatus(ConfirmOrderStatusEnum.INIT.getCode());
        confirmOrder.setTickets(JSON.toJSONString(tickets));
        confirmOrderMapper.insert(confirmOrder);

        // RabbitMQ：发送消息，消费者触发后置流程
        ConfirmOrderMQDto confirmOrderMQDto = new ConfirmOrderMQDto();
        confirmOrderMQDto.setDate(req.getDate());
        confirmOrderMQDto.setTrainCode(req.getTrainCode());
        confirmOrderMQDto.setLogId(MDC.get("LOG_ID"));
        confirmOrderMQDto.setConfirmOrderId(confirmOrder.getId());
        rabbitTemplate.convertAndSend(
                com.jiawa.train.business.config.RabbitMqConfig.CONFIRM_ORDER_EXCHANGE,
                com.jiawa.train.business.config.RabbitMqConfig.CONFIRM_ORDER_ROUTING_KEY,
                confirmOrderMQDto
        );


        return confirmOrder.getId();
    }

    /**
     * 随机采样车厢 + Lua 原子选座/占座。
     * 返回选中的座位（包含车厢/排/列信息，用于写入订单 tickets）。
     */
    private record ChosenSeat(Integer carriageIndex, Integer carriageSeatIndex) {}

    private ChosenSeat chooseAndOccupyOneSeatByLua(Date date, String trainCode, String seatType, Integer startIndex, Integer endIndex) {
        if (startIndex == null || endIndex == null || endIndex <= startIndex) {
            return null;
        }

        String dateStr = cn.hutool.core.date.DateUtil.formatDate(date);
        String carriageKey = REDIS_KEY_TRAIN_CARRIAGE_COUNT + "-" + dateStr + "-" + trainCode;
        String carriageJson = redisTemplate.opsForValue().get(carriageKey);
        if (StrUtil.isBlank(carriageJson)) {
            return null;
        }
        Map<String, List<Integer>> seatTypeToCarriages = JSON.parseObject(
                carriageJson,
                new TypeReference<Map<String, List<Integer>>>() {}
        );
        List<Integer> carriageIndexList = (seatTypeToCarriages == null) ? null : seatTypeToCarriages.get(seatType);
        if (carriageIndexList == null || carriageIndexList.isEmpty()) {
            return null;
        }

        // 不再按车厢逐个采样；保留校验逻辑即可

        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
                -- KEYS: segment bitmap keys (same carriage, 覆盖区间段)
                -- ARGV[1]: tmpKey
                -- ARGV[2]: maskKey
                -- return: seatBitPos (>=0) or -1
                local tmpKey = ARGV[1]
                local maskKey = ARGV[2]
                redis.call('BITOP','AND', tmpKey, unpack(KEYS))
                local pos = redis.call('BITPOS', tmpKey, 1)
                if (pos < 0) then
                  redis.call('DEL', tmpKey)
                  return -1
                end
                local bytes = redis.call('GETRANGE', tmpKey, 0, -1)
                redis.call('DEL', tmpKey)
                if (not bytes) then
                  return -1
                end
                local byteIndex = math.floor(pos / 8) + 1
                local bitInByte = pos % 8
                local b = string.byte(bytes, byteIndex)
                local bitMask = 2^(7-bitInByte)
                if (b >= bitMask) then
                  b = b - bitMask
                end
                bytes = string.sub(bytes, 1, byteIndex-1) .. string.char(b) .. string.sub(bytes, byteIndex+1)
                redis.call('SET', maskKey, bytes)
                for i=1,#KEYS do
                  redis.call('BITOP','AND', KEYS[i], KEYS[i], maskKey)
                end
                redis.call('DEL', maskKey)
                return pos
                """);

        // 已改为：车次-座位类型级 bitmap，不再按车厢循环选座；seatBitPos 通过映射换算车厢号
        List<String> keys = new ArrayList<>();
        for (int segmentIndex = startIndex; segmentIndex < endIndex; segmentIndex++) {
            keys.add(REDIS_KEY_SEAT_SELL_PRE + "-" + dateStr + "-" + trainCode + "-" + seatType + "-" + segmentIndex);
        }
        if (keys.isEmpty()) {
            return null;
        }

        String tmpKey = "TMP_AND-" + UUID.randomUUID();
        String maskKey = "TMP_MASK-" + UUID.randomUUID();
        Long seatBitPos = redisTemplate.execute(script, keys, tmpKey, maskKey);
        if (seatBitPos == null || seatBitPos < 0) {
            return null;
        }
        return mapSeatBitPosToCarriage(dateStr, trainCode, seatType, seatBitPos.intValue());
    }

    private ChosenSeat mapSeatBitPosToCarriage(String dateStr, String trainCode, String seatType, int seatBitPos) {
        if (seatBitPos < 0) {
            return null;
        }

        String carriageKey = REDIS_KEY_TRAIN_CARRIAGE_COUNT + "-" + dateStr + "-" + trainCode;
        String carriageJson = redisTemplate.opsForValue().get(carriageKey);
        if (StrUtil.isBlank(carriageJson)) {
            return null;
        }
        Map<String, List<Integer>> seatTypeToCarriages = JSON.parseObject(
                carriageJson,
                new TypeReference<Map<String, List<Integer>>>() {}
        );
        List<Integer> carriageIndexList = (seatTypeToCarriages == null) ? null : seatTypeToCarriages.get(seatType);
        if (CollUtil.isEmpty(carriageIndexList)) {
            return null;
        }

        String carriageSeatCountKey = REDIS_KEY_CARRIAGE_SEAT_COUNT_PRE + "-" + dateStr + "-" + trainCode;
        int offset = seatBitPos;
        for (Integer carriageIndex : carriageIndexList) {
            if (carriageIndex == null) {
                continue;
            }
            Object seatCountObj = redisTemplate.opsForHash().get(carriageSeatCountKey, String.valueOf(carriageIndex));
            Integer seatCount = seatCountObj == null ? null : Integer.valueOf(seatCountObj.toString());
            if (seatCount == null || seatCount <= 0) {
                continue;
            }
            if (offset < seatCount) {
                return new ChosenSeat(carriageIndex, offset + 1);
            }
            offset -= seatCount;
        }
        return null;
    }

    /**
     * 降级方法，需包含限流方法的所有参数和BlockException参数
     * @param req
     * @param e
     */
    public void beforeDoConfirmBlock(ConfirmOrderDoReq req, BlockException e) {
        LOG.info("购票请求被限流：{}", req);
        throw new BusinessException(BusinessExceptionEnum.CONFIRM_ORDER_FLOW_EXCEPTION);
    }
}
