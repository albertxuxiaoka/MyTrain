package com.jiawa.train.business.service;

import com.jiawa.train.business.domain.ConfirmOrder;
import com.jiawa.train.business.domain.DailyTrainSeat;
import com.jiawa.train.business.domain.DailyTrainTicket;
import com.jiawa.train.business.domain.ConfirmOrderExample;
import com.jiawa.train.business.domain.DailyTrainSeatExample;
import com.jiawa.train.business.enums.ConfirmOrderStatusEnum;
import com.jiawa.train.business.feign.MemberFeign;
import com.jiawa.train.business.mapper.ConfirmOrderMapper;
import com.jiawa.train.business.mapper.DailyTrainSeatMapper;
import com.jiawa.train.business.mapper.cust.DailyTrainTicketMapperCust;
import com.jiawa.train.business.req.ConfirmOrderTicketReq;
import com.jiawa.train.common.req.MemberTicketReq;
import com.jiawa.train.common.resp.CommonResp;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class AfterConfirmOrderService {

    private static final Logger LOG = LoggerFactory.getLogger(AfterConfirmOrderService.class);

    @Resource
    private DailyTrainSeatMapper dailyTrainSeatMapper;

    @Resource
    private DailyTrainTicketMapperCust dailyTrainTicketMapperCust;

    @Resource
    private MemberFeign memberFeign;

    @Resource
    private ConfirmOrderMapper confirmOrderMapper;

    @Resource
    private DailyTrainTicketService dailyTrainTicketService;

    /**
     * 选中座位后事务处理：
     *  座位表修改售卖情况sell；
     *  余票详情表修改余票；
     *  为会员增加购票记录
     *  更新确认订单为成功
     */
    // @Transactional
    // @GlobalTransactional
    public void afterDoConfirm(DailyTrainTicket dailyTrainTicket, List<DailyTrainSeat> finalSeatList, List<ConfirmOrderTicketReq> tickets, ConfirmOrder confirmOrder) throws Exception {
        // LOG.info("seata全局事务ID: {}", RootContext.getXID());
        for (int j = 0; j < finalSeatList.size(); j++) {
            DailyTrainSeat dailyTrainSeat = finalSeatList.get(j);
            DailyTrainSeat seatForUpdate = new DailyTrainSeat();
            seatForUpdate.setId(dailyTrainSeat.getId());
            seatForUpdate.setSell(dailyTrainSeat.getSell());
            seatForUpdate.setUpdateTime(new Date());
            dailyTrainSeatMapper.updateByPrimaryKeySelective(seatForUpdate);

            // 计算这个站卖出去后，影响了哪些站的余票库存
            // 参照2-3节 如何保证不超卖、不少卖，还要能承受极高的并发 10:30左右
            // 影响的库存：本次选座之前没卖过票的，和本次购买的区间有交集的区间
            // 假设10个站，本次买4~7站
            // 原售：001000001
            // 购买：000011100
            // 新售：001011101
            // 影响：XXX11111X
            // Integer startIndex = 4;
            // Integer endIndex = 7;
            // Integer minStartIndex = startIndex - 往前碰到的最后一个0;
            // Integer maxStartIndex = endIndex - 1;
            // Integer minEndIndex = startIndex + 1;
            // Integer maxEndIndex = endIndex + 往后碰到的最后一个0;
            Integer startIndex = dailyTrainTicket.getStartIndex();
            Integer endIndex = dailyTrainTicket.getEndIndex();
            char[] chars = seatForUpdate.getSell().toCharArray();
            Integer maxStartIndex = endIndex - 1;
            Integer minEndIndex = startIndex + 1;
            Integer minStartIndex = 0;
            for (int i = startIndex - 1; i >= 0; i--) {
                char aChar = chars[i];
                if (aChar == '1') {
                    minStartIndex = i + 1;
                    break;
                }
            }
            LOG.info("影响出发站区间：" + minStartIndex + "-" + maxStartIndex);

            Integer maxEndIndex = seatForUpdate.getSell().length();
            for (int i = endIndex; i < seatForUpdate.getSell().length(); i++) {
                char aChar = chars[i];
                if (aChar == '1') {
                    maxEndIndex = i;
                    break;
                }
            }
            LOG.info("影响到达站区间：" + minEndIndex + "-" + maxEndIndex);

            dailyTrainTicketMapperCust.updateCountBySell(
                    dailyTrainSeat.getDate(),
                    dailyTrainSeat.getTrainCode(),
                    dailyTrainSeat.getSeatType(),
                    minStartIndex,
                    maxStartIndex,
                    minEndIndex,
                    maxEndIndex);

            // 调用会员服务接口，为会员增加一张车票
            MemberTicketReq memberTicketReq = new MemberTicketReq();
            memberTicketReq.setMemberId(confirmOrder.getMemberId());
            memberTicketReq.setPassengerId(tickets.get(j).getPassengerId());
            memberTicketReq.setPassengerName(tickets.get(j).getPassengerName());
            memberTicketReq.setTrainDate(dailyTrainTicket.getDate());
            memberTicketReq.setTrainCode(dailyTrainTicket.getTrainCode());
            memberTicketReq.setCarriageIndex(dailyTrainSeat.getCarriageIndex());
            memberTicketReq.setSeatRow(dailyTrainSeat.getRow());
            memberTicketReq.setSeatCol(dailyTrainSeat.getCol());
            memberTicketReq.setStartStation(dailyTrainTicket.getStart());
            memberTicketReq.setStartTime(dailyTrainTicket.getStartTime());
            memberTicketReq.setEndStation(dailyTrainTicket.getEnd());
            memberTicketReq.setEndTime(dailyTrainTicket.getEndTime());
            memberTicketReq.setSeatType(dailyTrainSeat.getSeatType());
            CommonResp<Object> commonResp = memberFeign.save(memberTicketReq);
            LOG.info("调用member接口，返回：{}", commonResp);

            // 更新订单状态为成功
            ConfirmOrder confirmOrderForUpdate = new ConfirmOrder();
            confirmOrderForUpdate.setId(confirmOrder.getId());
            confirmOrderForUpdate.setUpdateTime(new Date());
            confirmOrderForUpdate.setStatus(ConfirmOrderStatusEnum.SUCCESS.getCode());
            confirmOrderMapper.updateByPrimaryKeySelective(confirmOrderForUpdate);

            // 模拟调用方出现异常
            // Thread.sleep(10000);
            // if (1 == 1) {
            //     throw new Exception("测试异常");
            // }
        }
    }

    /**
     * 模拟 MQ：异步处理订单后置流程
     * 1) 原子更新订单状态 INIT -> PENDING，失败则跳过（表示已处理过）
     * 2) 更新座位 sell 区间位为 1
     * 3) 生成会员车票
     * 4) 更新订单状态为 SUCCESS
     */
    @Async
    public void afterDoConfirmAsync(com.jiawa.train.business.dto.ConfirmOrderMQDto dto) {
        Long confirmOrderId = dto.getConfirmOrderId();
        if (confirmOrderId == null) {
            return;
        }

        ConfirmOrder confirmOrder = confirmOrderMapper.selectByPrimaryKey(confirmOrderId);
        if (confirmOrder == null) {
            return;
        }

        // 原子更新 INIT -> PENDING
        ConfirmOrderExample example = new ConfirmOrderExample();
        example.createCriteria()
                .andIdEqualTo(confirmOrderId)
                .andStatusEqualTo(ConfirmOrderStatusEnum.INIT.getCode());
        ConfirmOrder confirmOrderForUpdate = new ConfirmOrder();
        confirmOrderForUpdate.setStatus(ConfirmOrderStatusEnum.PENDING.getCode());
        confirmOrderForUpdate.setUpdateTime(new Date());
        int updated = confirmOrderMapper.updateByExampleSelective(confirmOrderForUpdate, example);
        if (updated == 0) {
            LOG.info("订单已被处理过，跳过：id={}", confirmOrderId);
            return;
        }

        // 重新读取（含 tickets blob）
        confirmOrder = confirmOrderMapper.selectByPrimaryKey(confirmOrderId);

        List<ConfirmOrderTicketReq> tickets = com.alibaba.fastjson.JSON.parseArray(confirmOrder.getTickets(), ConfirmOrderTicketReq.class);
        if (tickets == null || tickets.size() != 1) {
            return;
        }
        ConfirmOrderTicketReq ticket = tickets.get(0);

        DailyTrainTicket dailyTrainTicket = dailyTrainTicketService.selectByUnique(
                confirmOrder.getDate(),
                confirmOrder.getTrainCode(),
                confirmOrder.getStart(),
                confirmOrder.getEnd()
        );
        if (dailyTrainTicket == null) {
            return;
        }

        // 查询并更新座位 sell（只更新该座位，不再更新全区间余票）
        DailyTrainSeatExample seatExample = new DailyTrainSeatExample();
        seatExample.createCriteria()
                .andDateEqualTo(confirmOrder.getDate())
                .andTrainCodeEqualTo(confirmOrder.getTrainCode())
                .andCarriageIndexEqualTo(ticket.getCarriageIndex())
                .andCarriageSeatIndexEqualTo(ticket.getCarriageSeatIndex());
        List<DailyTrainSeat> seatList = dailyTrainSeatMapper.selectByExample(seatExample);
        if (seatList == null || seatList.isEmpty()) {
            return;
        }
        DailyTrainSeat seat = seatList.get(0);
        char[] chars = seat.getSell().toCharArray();
        for (int i = dailyTrainTicket.getStartIndex(); i < dailyTrainTicket.getEndIndex(); i++) {
            if (i >= 0 && i < chars.length) {
                chars[i] = '1';
            }
        }
//
//        00000
        DailyTrainSeat seatForUpdate = new DailyTrainSeat();
        seatForUpdate.setId(seat.getId());
        seatForUpdate.setSell(new String(chars));
        seatForUpdate.setUpdateTime(new Date());
        dailyTrainSeatMapper.updateByPrimaryKeySelective(seatForUpdate);

        // 调用 member 服务生成车票
        try {
            MemberTicketReq memberTicketReq = new MemberTicketReq();
            memberTicketReq.setMemberId(confirmOrder.getMemberId());
            memberTicketReq.setPassengerId(ticket.getPassengerId());
            memberTicketReq.setPassengerName(ticket.getPassengerName());
            memberTicketReq.setTrainDate(dailyTrainTicket.getDate());
            memberTicketReq.setTrainCode(dailyTrainTicket.getTrainCode());
            memberTicketReq.setCarriageIndex(ticket.getCarriageIndex());
            memberTicketReq.setSeatRow(ticket.getSeatRow());
            memberTicketReq.setSeatCol(ticket.getSeatCol());
            memberTicketReq.setStartStation(dailyTrainTicket.getStart());
            memberTicketReq.setStartTime(dailyTrainTicket.getStartTime());
            memberTicketReq.setEndStation(dailyTrainTicket.getEnd());
            memberTicketReq.setEndTime(dailyTrainTicket.getEndTime());
            memberTicketReq.setSeatType(seat.getSeatType());
            memberFeign.save(memberTicketReq);
        } catch (Exception e) {
            LOG.error("调用member生成车票失败，id={}", confirmOrderId, e);
            return;
        }

        // 更新订单状态为 SUCCESS
        ConfirmOrder successUpdate = new ConfirmOrder();
        successUpdate.setId(confirmOrderId);
        successUpdate.setUpdateTime(new Date());
        successUpdate.setStatus(ConfirmOrderStatusEnum.SUCCESS.getCode());
        confirmOrderMapper.updateByPrimaryKeySelective(successUpdate);
    }
}
