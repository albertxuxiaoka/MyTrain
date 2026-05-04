package com.jiawa.train.business.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.EnumUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.jiawa.train.business.domain.DailyTrainCarriage;
import com.jiawa.train.business.domain.DailyTrain;
import com.jiawa.train.business.domain.DailyTrainTicket;
import com.jiawa.train.business.domain.DailyTrainTicketExample;
import com.jiawa.train.business.domain.TrainStation;
import com.jiawa.train.business.enums.SeatTypeEnum;
import com.jiawa.train.business.enums.TrainTypeEnum;
import com.jiawa.train.business.mapper.DailyTrainTicketMapper;
import com.jiawa.train.business.req.DailyTrainTicketQueryReq;
import com.jiawa.train.business.req.DailyTrainTicketSaveReq;
import com.jiawa.train.business.resp.DailyTrainTicketQueryResp;
import com.jiawa.train.common.resp.PageResp;
import com.jiawa.train.common.util.SnowUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class DailyTrainTicketService {

    private static final Logger LOG = LoggerFactory.getLogger(DailyTrainTicketService.class);

    /**
     * 每个车厢、每个区间段的座位售卖bitmap
     * key = DAILY_TRAIN_TICKET_SELL-{yyyy-MM-dd}-{trainCode}-{carriageIndex}-{segmentIndex}
     * segmentIndex：0-based，对应相邻站点间的区间（长度 = stationCount - 1）
     */
    private static final String REDIS_KEY_SEAT_SELL_PRE = "DAILY_TRAIN_TICKET_SELL";

    private static final String REDIS_KEY_TRAIN_CARRIAGE_COUNT = "DAILY_TRAIN_CARRIAGE_COUNT";

    /**
     * 车次区间余票缓存（按“某日-车次-出发站序-到达站序”）
     * value: ydz,edz,rw,yw（逗号分隔）
     */
    private static final String REDIS_KEY_TICKET_COUNT_PRE = "DAILY_TRAIN_TICKET_COUNT";

    /**
     * 区间余票缓存时间（秒）
     */
    private static final long TICKET_COUNT_CACHE_TTL_SECONDS = 5 * 60;

    /**
     * 车次区间（S->E 过滤后的车次列表）缓存（秒）
     */
    private static final long TICKET_QUERY_CACHE_TTL_SECONDS = 5 * 60;

    private static final String REDIS_KEY_TICKET_QUERY_PRE = "DAILY_TRAIN_TICKET_QUERY";

    @Resource
    private DailyTrainTicketMapper dailyTrainTicketMapper;

    @Resource
    private TrainStationService trainStationService;

    @Resource
    private DailyTrainSeatService dailyTrainSeatService;

    @Resource
    private DailyTrainCarriageService dailyTrainCarriageService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    public void save(DailyTrainTicketSaveReq req) {
        DateTime now = DateTime.now();
        DailyTrainTicket dailyTrainTicket = BeanUtil.copyProperties(req, DailyTrainTicket.class);
        if (ObjectUtil.isNull(dailyTrainTicket.getId())) {
            dailyTrainTicket.setId(SnowUtil.getSnowflakeNextId());
            dailyTrainTicket.setCreateTime(now);
            dailyTrainTicket.setUpdateTime(now);
            dailyTrainTicketMapper.insert(dailyTrainTicket);
        } else {
            dailyTrainTicket.setUpdateTime(now);
            dailyTrainTicketMapper.updateByPrimaryKey(dailyTrainTicket);
        }
    }

    @Cacheable(value = "DailyTrainTicketService.queryList3")
    public PageResp<DailyTrainTicketQueryResp> queryList3(DailyTrainTicketQueryReq req) {
        LOG.info("测试缓存击穿");
        return null;
    }

    @CachePut(value = "DailyTrainTicketService.queryList")
    public PageResp<DailyTrainTicketQueryResp> queryList2(DailyTrainTicketQueryReq req) {
        return queryList(req);
    }

    // @Cacheable(value = "DailyTrainTicketService.queryList")
    public PageResp<DailyTrainTicketQueryResp> queryList(DailyTrainTicketQueryReq req) {
        // 先查“车次区间（S->E）”缓存（缓存 5 分钟）
        if (ObjUtil.isNotNull(req.getDate())
                && ObjUtil.isNotEmpty(req.getStart())
                && ObjUtil.isNotEmpty(req.getEnd())) {
            String cacheKey = buildTicketQueryCacheKey(req);
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (StrUtil.isNotBlank(cached)) {
                PageResp<DailyTrainTicketQueryResp> pageResp = JSON.parseObject(
                        cached,
                        new TypeReference<PageResp<DailyTrainTicketQueryResp>>() {}
                );
                if (pageResp != null && CollUtil.isNotEmpty(pageResp.getList())) {
                    fillTicketCountByBitmapAndCache(pageResp.getList());
                }
                return pageResp;
            }
        }
        // 常见的缓存过期策略
        // TTL 超时时间
        // LRU 最近最少使用
        // LFU 最近最不经常使用
        // FIFO 先进先出
        // Random 随机淘汰策略
        // 去缓存里取数据，因数据库本身就没数据而造成缓存穿透
        // if (有数据) { null []
        //     return
        // } else {
        //     去数据库取数据
        // }
        DailyTrainTicketExample dailyTrainTicketExample = new DailyTrainTicketExample();
        dailyTrainTicketExample.setOrderByClause("`date` desc, start_time asc, train_code asc, `start_index` asc, `end_index` asc");
        DailyTrainTicketExample.Criteria criteria = dailyTrainTicketExample.createCriteria();
        if (ObjUtil.isNotNull(req.getDate())) {
            criteria.andDateEqualTo(req.getDate());
        }
        if (ObjUtil.isNotEmpty(req.getTrainCode())) {
            criteria.andTrainCodeEqualTo(req.getTrainCode());
        }
        if (ObjUtil.isNotEmpty(req.getStart())) {
            criteria.andStartEqualTo(req.getStart());
        }
        if (ObjUtil.isNotEmpty(req.getEnd())) {
            criteria.andEndEqualTo(req.getEnd());
        }

        LOG.info("查询页码：{}", req.getPage());
        LOG.info("每页条数：{}", req.getSize());
        PageHelper.startPage(req.getPage(), req.getSize());
        List<DailyTrainTicket> dailyTrainTicketList = dailyTrainTicketMapper.selectByExample(dailyTrainTicketExample);

        PageInfo<DailyTrainTicket> pageInfo = new PageInfo<>(dailyTrainTicketList);
        LOG.info("总行数：{}", pageInfo.getTotal());
        LOG.info("总页数：{}", pageInfo.getPages());

        List<DailyTrainTicketQueryResp> list = BeanUtil.copyToList(dailyTrainTicketList, DailyTrainTicketQueryResp.class);

        // 余票查询（对外）：优先取“车次区间余票缓存”，未命中则用 Redis bitmap 计算并回填缓存
        // 说明：
        // 1) 车次列表仍来自 daily_train_ticket（静态区间表思路），但余票不直接用 DB 字段
        // 2) “车厢级区间 bitmap”只用于实时计算，不在这里手动写入/维护
        if (ObjUtil.isNotNull(req.getDate())
                && ObjUtil.isNotEmpty(req.getStart())
                && ObjUtil.isNotEmpty(req.getEnd())
                && CollUtil.isNotEmpty(list)) {
            fillTicketCountByBitmapAndCache(list);
        }

        PageResp<DailyTrainTicketQueryResp> pageResp = new PageResp<>();
        pageResp.setTotal(pageInfo.getTotal());
        pageResp.setList(list);

        // 回填“车次区间（S->E）”缓存（5分钟）
        if (ObjUtil.isNotNull(req.getDate())
                && ObjUtil.isNotEmpty(req.getStart())
                && ObjUtil.isNotEmpty(req.getEnd())) {
            String cacheKey = buildTicketQueryCacheKey(req);
            redisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(pageResp), TICKET_QUERY_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        }
        return pageResp;
    }

    public void delete(Long id) {
        dailyTrainTicketMapper.deleteByPrimaryKey(id);
    }

    @Transactional
    public void genDaily(DailyTrain dailyTrain, Date date, String trainCode) {
        LOG.info("生成日期【{}】车次【{}】的余票信息开始", DateUtil.formatDate(date), trainCode);

        // 删除某日某车次的余票信息
        DailyTrainTicketExample dailyTrainTicketExample = new DailyTrainTicketExample();
        dailyTrainTicketExample.createCriteria()
                .andDateEqualTo(date)
                .andTrainCodeEqualTo(trainCode);
        dailyTrainTicketMapper.deleteByExample(dailyTrainTicketExample);

        // 查出某车次的所有的车站信息
        List<TrainStation> stationList = trainStationService.selectByTrainCode(trainCode);
        if (CollUtil.isEmpty(stationList)) {
            LOG.info("该车次没有车站基础数据，生成该车次的余票信息结束");
            return;
        }

        // 初始化每车厢、每区间段的座位售卖bitmap（全部可售：bit=1）
        initSeatSellBitmaps(date, trainCode, stationList.size());

        DateTime now = DateTime.now();
        int ydz = dailyTrainSeatService.countSeat(date, trainCode, SeatTypeEnum.YDZ.getCode());
        int edz = dailyTrainSeatService.countSeat(date, trainCode, SeatTypeEnum.EDZ.getCode());
        int rw = dailyTrainSeatService.countSeat(date, trainCode, SeatTypeEnum.RW.getCode());
        int yw = dailyTrainSeatService.countSeat(date, trainCode, SeatTypeEnum.YW.getCode());
        for (int i = 0; i < stationList.size(); i++) {
            // 得到出发站
            TrainStation trainStationStart = stationList.get(i);
            BigDecimal sumKM = BigDecimal.ZERO;
            for (int j = (i + 1); j < stationList.size(); j++) {
                TrainStation trainStationEnd = stationList.get(j);
                sumKM = sumKM.add(trainStationEnd.getKm());

                DailyTrainTicket dailyTrainTicket = new DailyTrainTicket();
                dailyTrainTicket.setId(SnowUtil.getSnowflakeNextId());
                dailyTrainTicket.setDate(date);
                dailyTrainTicket.setTrainCode(trainCode);
                dailyTrainTicket.setStart(trainStationStart.getName());
                dailyTrainTicket.setStartPinyin(trainStationStart.getNamePinyin());
                dailyTrainTicket.setStartTime(trainStationStart.getOutTime());
                dailyTrainTicket.setStartIndex(trainStationStart.getIndex());
                dailyTrainTicket.setEnd(trainStationEnd.getName());
                dailyTrainTicket.setEndPinyin(trainStationEnd.getNamePinyin());
                dailyTrainTicket.setEndTime(trainStationEnd.getInTime());
                dailyTrainTicket.setEndIndex(trainStationEnd.getIndex());

                // 票价 = 里程之和 * 座位单价 * 车次类型系数
                String trainType = dailyTrain.getType();
                // 计算票价系数：TrainTypeEnum.priceRate
                BigDecimal priceRate = EnumUtil.getFieldBy(TrainTypeEnum::getPriceRate, TrainTypeEnum::getCode, trainType);
                BigDecimal ydzPrice = sumKM.multiply(SeatTypeEnum.YDZ.getPrice()).multiply(priceRate).setScale(2, RoundingMode.HALF_UP);
                BigDecimal edzPrice = sumKM.multiply(SeatTypeEnum.EDZ.getPrice()).multiply(priceRate).setScale(2, RoundingMode.HALF_UP);
                BigDecimal rwPrice = sumKM.multiply(SeatTypeEnum.RW.getPrice()).multiply(priceRate).setScale(2, RoundingMode.HALF_UP);
                BigDecimal ywPrice = sumKM.multiply(SeatTypeEnum.YW.getPrice()).multiply(priceRate).setScale(2, RoundingMode.HALF_UP);
                dailyTrainTicket.setYdz(ydz);
                dailyTrainTicket.setYdzPrice(ydzPrice);
                dailyTrainTicket.setEdz(edz);
                dailyTrainTicket.setEdzPrice(edzPrice);
                dailyTrainTicket.setRw(rw);
                dailyTrainTicket.setRwPrice(rwPrice);
                dailyTrainTicket.setYw(yw);
                dailyTrainTicket.setYwPrice(ywPrice);
                dailyTrainTicket.setCreateTime(now);
                dailyTrainTicket.setUpdateTime(now);
                dailyTrainTicketMapper.insert(dailyTrainTicket);
            }
        }
        LOG.info("生成日期【{}】车次【{}】的余票信息结束", DateUtil.formatDate(date), trainCode);

    }

    private void initSeatSellBitmaps(Date date, String trainCode, int stationCount) {
        int segmentCount = stationCount - 1;
        if (segmentCount <= 0) {
            return;
        }

        List<DailyTrainCarriage> carriageList = dailyTrainCarriageService.selectByTrainCode(date, trainCode);
        if (CollUtil.isEmpty(carriageList)) {
            LOG.info("该车次没有车厢数据，跳过座位bitmap初始化：date={}, trainCode={}", DateUtil.formatDate(date), trainCode);
            return;
        }

        String dateStr = DateUtil.formatDate(date);
        // 存放“车厢类型 -> 车厢号列表”，用于查询余票时不查库
        // key: DAILY_TRAIN_CARRIAGE_COUNT-{yyyy-MM-dd}-{trainCode}
        // value(JSON): {"1":[1,2,3],"2":[4,5]...}
        Map<String, List<Integer>> seatTypeToCarriages = new HashMap<>();
        for (DailyTrainCarriage carriage : carriageList) {
            if (carriage.getIndex() == null || StrUtil.isBlank(carriage.getSeatType())) {
                continue;
            }
            seatTypeToCarriages.computeIfAbsent(carriage.getSeatType(), k -> new ArrayList<>()).add(carriage.getIndex());
        }
        redisTemplate.opsForValue().set(
                REDIS_KEY_TRAIN_CARRIAGE_COUNT + "-" + dateStr + "-" + trainCode,
                JSON.toJSONString(seatTypeToCarriages)
        );
        RedisConnection connection = redisTemplate.getConnectionFactory().getConnection();

        for (DailyTrainCarriage carriage : carriageList) {
            Integer carriageIndex = carriage.getIndex();
            Integer seatCount = carriage.getSeatCount();
            if (carriageIndex == null || seatCount == null || seatCount <= 0) {
                continue;
            }

            byte[] fullSellBytes = buildFullSellBytes(seatCount);
            for (int segmentIndex = 0; segmentIndex < segmentCount; segmentIndex++) {
                String key = REDIS_KEY_SEAT_SELL_PRE + "-" + dateStr + "-" + trainCode  + "-" + carriageIndex + "-" + segmentIndex;
                connection.set(
                        key.getBytes(StandardCharsets.UTF_8),
                        fullSellBytes
                );
            }
        }
        connection.close();
    }

    private byte[] buildFullSellBytes(int seatCount) {
        int byteLen = (seatCount + 7) / 8;
        byte[] bytes = new byte[byteLen];
        Arrays.fill(bytes, (byte) 0xFF);

        int remain = seatCount % 8;
        if (remain != 0) {
            int mask = (1 << remain) - 1;
            bytes[byteLen - 1] = (byte) (bytes[byteLen - 1] & mask);
        }
        return bytes;
    }

    public DailyTrainTicket selectByUnique(Date date, String trainCode, String start, String end) {
        DailyTrainTicketExample dailyTrainTicketExample = new DailyTrainTicketExample();
        dailyTrainTicketExample.createCriteria()
                .andDateEqualTo(date)
                .andTrainCodeEqualTo(trainCode)
                .andStartEqualTo(start)
                .andEndEqualTo(end);
        List<DailyTrainTicket> list = dailyTrainTicketMapper.selectByExample(dailyTrainTicketExample);
        if (CollUtil.isNotEmpty(list)) {
            return list.get(0);
        } else {
            return null;
        }
    }

    private void fillTicketCountByBitmapAndCache(List<DailyTrainTicketQueryResp> list) {
        // 1) 先批量查“车次区间余票缓存”
        List<String> cacheKeys = new ArrayList<>(list.size());
        for (DailyTrainTicketQueryResp resp : list) {
            cacheKeys.add(buildTicketCountCacheKey(resp.getDate(), resp.getTrainCode(), resp.getStartIndex(), resp.getEndIndex()));
        }
        List<String> cacheValues = redisTemplate.opsForValue().multiGet(cacheKeys);

        // 2) 命中的直接回填；未命中的收集起来统一计算
        Map<Integer, String> missIndexToKey = new HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            String v = (cacheValues == null) ? null : cacheValues.get(i);
            if (StrUtil.isNotBlank(v)) {
                applyTicketCountFromCache(list.get(i), v);
            } else {
                missIndexToKey.put(i, cacheKeys.get(i));
            }
        }

        if (missIndexToKey.isEmpty()) {
            return;
        }

        // 3) 对未命中的，批量用 bitmap 实时计算，然后回填缓存
        for (Map.Entry<Integer, String> entry : missIndexToKey.entrySet()) {
            int idx = entry.getKey();
            String cacheKey = entry.getValue();
            DailyTrainTicketQueryResp resp = list.get(idx);

            Integer ydz = countSeatTypeByBitmap(resp.getDate(), resp.getTrainCode(), SeatTypeEnum.YDZ.getCode(), resp.getStartIndex(), resp.getEndIndex());
            Integer edz = countSeatTypeByBitmap(resp.getDate(), resp.getTrainCode(), SeatTypeEnum.EDZ.getCode(), resp.getStartIndex(), resp.getEndIndex());
//            下面这个别改，我硬编码为-1，表明没有这个座位类型的座位！！！
            Integer rw = -1;//countSeatTypeByBitmap(resp.getDate(), resp.getTrainCode(), SeatTypeEnum.RW.getCode(), resp.getStartIndex(), resp.getEndIndex());
            Integer yw = -1;//countSeatTypeByBitmap(resp.getDate(), resp.getTrainCode(), SeatTypeEnum.YW.getCode(), resp.getStartIndex(), resp.getEndIndex());

            resp.setYdz(ydz);
            resp.setEdz(edz);
            resp.setRw(rw);
            resp.setYw(yw);

            String cacheValue = buildTicketCountCacheValue(ydz, edz, rw, yw);
            redisTemplate.opsForValue().set(cacheKey, cacheValue, TICKET_COUNT_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        }
    }

    private String buildTicketCountCacheKey(Date date, String trainCode, Integer startIndex, Integer endIndex) {
        String dateStr = DateUtil.formatDate(date);
        return REDIS_KEY_TICKET_COUNT_PRE + "-" + dateStr + "-" + trainCode + "-" + startIndex + "-" + endIndex;
    }

    private String buildTicketCountCacheValue(Integer ydz, Integer edz, Integer rw, Integer yw) {
        return ydz + "," + edz + "," + rw + "," + yw;
    }

    private void applyTicketCountFromCache(DailyTrainTicketQueryResp resp, String cacheValue) {
        List<String> parts = StrUtil.splitTrim(cacheValue, ",");
        if (CollUtil.size(parts) != 4) {
            return;
        }
        resp.setYdz(NumberUtil.parseInt(parts.get(0)));
        resp.setEdz(NumberUtil.parseInt(parts.get(1)));
        resp.setRw(NumberUtil.parseInt(parts.get(2)));
        resp.setYw(NumberUtil.parseInt(parts.get(3)));
    }

    /**
     * 用 Redis bitmap 计算某座位类型在区间 [startIndex, endIndex) 的可售座位数
     */
    private Integer countSeatTypeByBitmap(Date date, String trainCode, String seatType, Integer startIndex, Integer endIndex) {
        if (startIndex == null || endIndex == null || endIndex <= startIndex) {
            return 0;
        }

        String dateStr = DateUtil.formatDate(date);

        // 不再查库获取车厢：依赖 initSeatSellBitmaps 写入的 Redis 映射
        String carriageKey = REDIS_KEY_TRAIN_CARRIAGE_COUNT + "-" + dateStr + "-" + trainCode;
        String carriageJson = redisTemplate.opsForValue().get(carriageKey);
        if (StrUtil.isBlank(carriageJson)) {
            return 0;
        }
        Map<String, List<Integer>> seatTypeToCarriages = JSON.parseObject(
                carriageJson,
                new TypeReference<Map<String, List<Integer>>>() {}
        );
        List<Integer> carriageIndexList = (seatTypeToCarriages == null) ? null : seatTypeToCarriages.get(seatType);
        if (CollUtil.isEmpty(carriageIndexList)) {
            return 0;
        }

        // 复用原有逻辑：构造一个只包含 index 的 carriageList
        List<DailyTrainCarriage> carriageList = new ArrayList<>();
        for (Integer carriageIndex : carriageIndexList) {
            if (carriageIndex == null) {
                continue;
            }
            DailyTrainCarriage carriage = new DailyTrainCarriage();
            carriage.setIndex(carriageIndex);
            carriageList.add(carriage);
        }
        if (CollUtil.isEmpty(carriageList)) {
            return 0;
        }
        RedisConnection connection = redisTemplate.getConnectionFactory().getConnection();
        try {
            long total = 0L;
            for (DailyTrainCarriage carriage : carriageList) {
                Integer carriageIndex = carriage.getIndex();
                if (carriageIndex == null) {
                    continue;
                }

                List<byte[]> srcKeys = new ArrayList<>();
                for (int segmentIndex = startIndex; segmentIndex < endIndex; segmentIndex++) {
                    String key = REDIS_KEY_SEAT_SELL_PRE + "-" + dateStr + "-" + trainCode + "-" + carriageIndex + "-" + segmentIndex;
                    srcKeys.add(key.getBytes(StandardCharsets.UTF_8));
                }
                if (srcKeys.isEmpty()) {
                    continue;
                }

                // AND 运算的临时 key：不做业务级缓存，只用于本次计算
                String tmpKey = "TMP_AND-" + UUID.randomUUID();
                byte[] tmpKeyBytes = tmpKey.getBytes(StandardCharsets.UTF_8);

                connection.bitOp(RedisConnection.BitOperation.AND, tmpKeyBytes, srcKeys.toArray(new byte[0][]));
                Long cnt = connection.bitCount(tmpKeyBytes);
                connection.del(tmpKeyBytes);
                if (cnt != null) {
                    total += cnt;
                }
            }
            return (int) total;
        } finally {
            connection.close();
        }
    }

    private String buildTicketQueryCacheKey(DailyTrainTicketQueryReq req) {
        // 把查询条件（含分页）纳入 key，避免不同分页互相污染
        String dateStr = (req.getDate() == null) ? "" : DateUtil.formatDate(req.getDate());
        String trainCode = StrUtil.blankToDefault(req.getTrainCode(), "");
        String start = StrUtil.blankToDefault(req.getStart(), "");
        String end = StrUtil.blankToDefault(req.getEnd(), "");
        return REDIS_KEY_TICKET_QUERY_PRE
                + "-" + dateStr
                + "-" + trainCode
                + "-" + start
                + "-" + end
                + "-" + req.getPage()
                + "-" + req.getSize();
    }
}
