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
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.jiawa.train.business.domain.DailyTrain;
import com.jiawa.train.business.domain.DailyTrainCarriage;
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
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class DailyTrainTicketService {

    private static final Logger LOG = LoggerFactory.getLogger(DailyTrainTicketService.class);

    /**
     * 车次-座位类型级区间段座位售卖 bitmap（bit=1 表示可售，bit=0 表示已售）
     * key = DAILY_TRAIN_TICKET_SELL-{yyyy-MM-dd}-{trainCode}-{seatType}-{segmentIndex}
     * segmentIndex: 0-based，对应相邻站点间的区间（长度 = stationCount - 1）
     */
    private static final String REDIS_KEY_SEAT_SELL_PRE = "DAILY_TRAIN_TICKET_SELL";

    /**
     * 维护：座位类型 -> 车厢号列表（仍保持不变，用于由 seatType bitmap 位号换算车厢号）
     * key: DAILY_TRAIN_CARRIAGE_COUNT-{yyyy-MM-dd}-{trainCode}
     * value(JSON): {"1":[1,2,3],"2":[4,5]...}
     */
    private static final String REDIS_KEY_TRAIN_CARRIAGE_COUNT = "DAILY_TRAIN_CARRIAGE_COUNT";

    /**
     * 维护：车厢号 -> 车厢总座位数（用于 seatType bitmap 位号换算车厢内座位序号）
     * key: DAILY_TRAIN_CARRIAGE_SEAT_COUNT-{yyyy-MM-dd}-{trainCode}
     * field: carriageIndex
     * value: seatCount
     */
    private static final String REDIS_KEY_CARRIAGE_SEAT_COUNT_PRE = "DAILY_TRAIN_CARRIAGE_SEAT_COUNT";

    /**
     * 站名 -> 站序（用于购票时快速获取 startIndex/endIndex）
     * key: DAILY_TRAIN_STATION_INDEX-{yyyy-MM-dd}-{trainCode}
     * field: stationName
     * value: stationIndex
     */
    private static final String REDIS_KEY_STATION_INDEX_PRE = "DAILY_TRAIN_STATION_INDEX";

    /**
     * 车次区间余票缓存（按“某日-车次-出发站序-到达站序”）
     * value: ydz,edz,rw,yw（逗号分隔）
     */
    private static final String REDIS_KEY_TICKET_COUNT_PRE = "DAILY_TRAIN_TICKET_COUNT";

    /**
     * 区间余票缓存时间（秒）——按需求改为 1min
     */
    private static final long TICKET_COUNT_CACHE_TTL_SECONDS = 60;

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

    @Cacheable(value = "dailyTrainTicketQuery", key = "#req.date+'_'+#req.start+'_'+#req.end+'_'+#req.page+'_'+#req.size")
    public PageResp<DailyTrainTicketQueryResp> queryList(DailyTrainTicketQueryReq req) {
        // 车次区间列表缓存（保持原逻辑）
        if (ObjUtil.isNotNull(req.getDate())
                && ObjUtil.isNotEmpty(req.getStart())
                && ObjUtil.isNotEmpty(req.getEnd())) {
            String cacheKey = buildTicketQueryCacheKey(req);
            String cacheValue = redisTemplate.opsForValue().get(cacheKey);
            if (StrUtil.isNotBlank(cacheValue)) {
                try {
                    return JSON.parseObject(cacheValue, PageResp.class);
                } catch (Exception ignore) {
                }
            }
        }

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

        PageHelper.startPage(req.getPage(), req.getSize());
        List<DailyTrainTicket> dailyTrainTicketList = dailyTrainTicketMapper.selectByExample(dailyTrainTicketExample);
        PageInfo<DailyTrainTicket> pageInfo = new PageInfo<>(dailyTrainTicketList);
        List<DailyTrainTicketQueryResp> list = BeanUtil.copyToList(dailyTrainTicketList, DailyTrainTicketQueryResp.class);

        // 余票查询（对外）：优先取“车次区间余票缓存”，未命中则用 Redis bitmap 计算并回填缓存
        if (ObjUtil.isNotNull(req.getDate())
                && ObjUtil.isNotEmpty(req.getStart())
                && ObjUtil.isNotEmpty(req.getEnd())
                && CollUtil.isNotEmpty(list)) {
            fillTicketCountByBitmapAndCache(list);
        }

        PageResp<DailyTrainTicketQueryResp> pageResp = new PageResp<>();
        pageResp.setTotal(pageInfo.getTotal());
        pageResp.setList(list);

        if (ObjUtil.isNotNull(req.getDate())
                && ObjUtil.isNotEmpty(req.getStart())
                && ObjUtil.isNotEmpty(req.getEnd())) {
            String cacheKey = buildTicketQueryCacheKey(req);
            redisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(pageResp), TICKET_QUERY_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        }
        return pageResp;
    }

    /**
     * 兼容旧接口：历史上用于不同缓存策略的实现。
     * 当前统一复用 queryList（余票缓存逻辑已按新 bitmap 方式实现）。
     */
    public PageResp<DailyTrainTicketQueryResp> queryList2(DailyTrainTicketQueryReq req) {
        return queryList(req);
    }

    /**
     * 兼容旧接口：历史上用于不同缓存策略的实现。
     * 当前统一复用 queryList（余票缓存逻辑已按新 bitmap 方式实现）。
     */
    public PageResp<DailyTrainTicketQueryResp> queryList3(DailyTrainTicketQueryReq req) {
        return queryList(req);
    }

    public void delete(Long id) {
        dailyTrainTicketMapper.deleteByPrimaryKey(id);
    }

    @Transactional
    public void genDaily(DailyTrain dailyTrain, Date date, String trainCode) {
        LOG.info("生成日期[{}]车次[{}]的余票信息开始", DateUtil.formatDate(date), trainCode);

        DailyTrainTicketExample dailyTrainTicketExample = new DailyTrainTicketExample();
        dailyTrainTicketExample.createCriteria()
                .andDateEqualTo(date)
                .andTrainCodeEqualTo(trainCode);
        dailyTrainTicketMapper.deleteByExample(dailyTrainTicketExample);

        List<TrainStation> stationList = trainStationService.selectByTrainCode(trainCode);
        if (CollUtil.isEmpty(stationList)) {
            LOG.info("该车次无车站基础数据，跳过生成余票信息：date={}, trainCode={}", DateUtil.formatDate(date), trainCode);
            return;
        }

        // 维护站名->站序到 Redis
        String dateStr = DateUtil.formatDate(date);
        String stationIndexKey = REDIS_KEY_STATION_INDEX_PRE + "-" + dateStr + "-" + trainCode;
        Map<String, String> stationIndexMap = new HashMap<>();
        for (TrainStation station : stationList) {
            if (station == null || StrUtil.isBlank(station.getName()) || station.getIndex() == null) {
                continue;
            }
            stationIndexMap.put(station.getName(), String.valueOf(station.getIndex()));
        }
        redisTemplate.delete(stationIndexKey);
        if (CollUtil.isNotEmpty(stationIndexMap)) {
            redisTemplate.opsForHash().putAll(stationIndexKey, stationIndexMap);
        }

        // 初始化：车次-座位类型级 bitmap
        initSeatSellBitmaps(date, trainCode, stationList.size());

        DateTime now = DateTime.now();
        int ydz = 0;
        int edz = 0;
        int rw = 0;
        int yw = 0;

        for (int i = 0; i < stationList.size(); i++) {
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

                String trainType = dailyTrain.getType();
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

        LOG.info("生成日期[{}]车次[{}]的余票信息结束", DateUtil.formatDate(date), trainCode);
    }

    private void initSeatSellBitmaps(Date date, String trainCode, int stationCount) {
        int segmentCount = stationCount - 1;
        if (segmentCount <= 0) {
            return;
        }

        List<DailyTrainCarriage> carriageList = dailyTrainCarriageService.selectByTrainCode(date, trainCode);
        if (CollUtil.isEmpty(carriageList)) {
            LOG.info("该车次无车厢数据，跳过座位bitmap初始化：date={}, trainCode={}", DateUtil.formatDate(date), trainCode);
            return;
        }

        String dateStr = DateUtil.formatDate(date);

        // 1) 维护 seatType -> carriageIndex 列表（有序）
        Map<String, List<Integer>> seatTypeToCarriages = new HashMap<>();
        // 2) 维护 carriageIndex -> seatCount（Hash）
        Map<String, String> carriageSeatCountMap = new HashMap<>();
        for (DailyTrainCarriage carriage : carriageList) {
            if (carriage == null || carriage.getIndex() == null) {
                continue;
            }
            if (StrUtil.isNotBlank(carriage.getSeatType())) {
                seatTypeToCarriages.computeIfAbsent(carriage.getSeatType(), k -> new ArrayList<>()).add(carriage.getIndex());
            }
            if (carriage.getSeatCount() != null) {
                carriageSeatCountMap.put(String.valueOf(carriage.getIndex()), String.valueOf(carriage.getSeatCount()));
            }
        }
        for (List<Integer> indices : seatTypeToCarriages.values()) {
            if (indices != null) {
                indices.sort(Comparator.naturalOrder());
            }
        }
        redisTemplate.opsForValue().set(
                REDIS_KEY_TRAIN_CARRIAGE_COUNT + "-" + dateStr + "-" + trainCode,
                JSON.toJSONString(seatTypeToCarriages)
        );

        String carriageSeatCountKey = REDIS_KEY_CARRIAGE_SEAT_COUNT_PRE + "-" + dateStr + "-" + trainCode;
        redisTemplate.delete(carriageSeatCountKey);
        if (CollUtil.isNotEmpty(carriageSeatCountMap)) {
            redisTemplate.opsForHash().putAll(carriageSeatCountKey, carriageSeatCountMap);
        }

        // 3) 初始化 seatType 级 bitmap：长度=该 seatType 的所有车厢 seatCount 之和
        Map<String, Integer> seatTypeToTotalSeatCount = new HashMap<>();
        Map<Integer, DailyTrainCarriage> carriageByIndex = new HashMap<>();
        for (DailyTrainCarriage carriage : carriageList) {
            if (carriage != null && carriage.getIndex() != null) {
                carriageByIndex.put(carriage.getIndex(), carriage);
            }
        }

        for (Map.Entry<String, List<Integer>> entry : seatTypeToCarriages.entrySet()) {
            String seatType = entry.getKey();
            List<Integer> indices = entry.getValue();
            if (StrUtil.isBlank(seatType) || CollUtil.isEmpty(indices)) {
                continue;
            }
            int totalSeatCount = 0;
            for (Integer carriageIndex : indices) {
                DailyTrainCarriage carriage = carriageByIndex.get(carriageIndex);
                Integer seatCount = carriage == null ? null : carriage.getSeatCount();
                if (seatCount != null && seatCount > 0) {
                    totalSeatCount += seatCount;
                }
            }
            if (totalSeatCount > 0) {
                seatTypeToTotalSeatCount.put(seatType, totalSeatCount);
            }
        }

        RedisConnection connection = redisTemplate.getConnectionFactory().getConnection();
        try {
            for (Map.Entry<String, Integer> entry : seatTypeToTotalSeatCount.entrySet()) {
                String seatType = entry.getKey();
                Integer totalSeatCount = entry.getValue();
                if (StrUtil.isBlank(seatType) || totalSeatCount == null || totalSeatCount <= 0) {
                    continue;
                }
                byte[] fullSellBytes = buildFullSellBytes(totalSeatCount);
                for (int segmentIndex = 0; segmentIndex < segmentCount; segmentIndex++) {
                    String key = REDIS_KEY_SEAT_SELL_PRE + "-" + dateStr + "-" + trainCode + "-" + seatType + "-" + segmentIndex;
                    connection.set(key.getBytes(StandardCharsets.UTF_8), fullSellBytes);
                }
            }
        } finally {
            connection.close();
        }
    }

    private byte[] buildFullSellBytes(int seatCount) {
        int byteLen = (seatCount + 7) / 8;
        byte[] bytes = new byte[byteLen];
        Arrays.fill(bytes, (byte) 0xFF);
        int remain = seatCount % 8;
        if (remain != 0) {
            // Redis bitmap 的 bit 序号在单字节内是从高位到低位（MSB -> LSB）。
            // seatCount 不是 8 的倍数时，应保留“高位的 remain 个 bit”为 1，其余低位清 0，
            // 否则会出现形如 00011111 的尾字节，导致 BITPOS 得到的 seatBitPos 偏大。
            int mask = 0xFF << (8 - remain);
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
        return CollUtil.isNotEmpty(list) ? list.get(0) : null;
    }

    private void fillTicketCountByBitmapAndCache(List<DailyTrainTicketQueryResp> list) {
        // 1) 批量查“车次区间余票缓存”
        List<String> cacheKeys = new ArrayList<>(list.size());
        for (DailyTrainTicketQueryResp resp : list) {
            cacheKeys.add(buildTicketCountCacheKey(resp.getDate(), resp.getTrainCode(), resp.getStartIndex(), resp.getEndIndex()));
        }
        List<String> cacheValues = redisTemplate.opsForValue().multiGet(cacheKeys);

        // 2) 命中则回填；未命中则收集统一计算
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

        // 3) 未命中的：用 bitmap 实时计算，再回填缓存（TTL 1min）
        for (Map.Entry<Integer, String> entry : missIndexToKey.entrySet()) {
            int idx = entry.getKey();
            String cacheKey = entry.getValue();
            DailyTrainTicketQueryResp resp = list.get(idx);

            Integer ydz = countSeatTypeByBitmap(resp.getDate(), resp.getTrainCode(), SeatTypeEnum.YDZ.getCode(), resp.getStartIndex(), resp.getEndIndex());
            Integer edz = countSeatTypeByBitmap(resp.getDate(), resp.getTrainCode(), SeatTypeEnum.EDZ.getCode(), resp.getStartIndex(), resp.getEndIndex());
            Integer rw = -1;
            Integer yw = -1;

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
        List<byte[]> srcKeys = new ArrayList<>();
        for (int segmentIndex = startIndex; segmentIndex < endIndex; segmentIndex++) {
            String key = REDIS_KEY_SEAT_SELL_PRE + "-" + dateStr + "-" + trainCode + "-" + seatType + "-" + segmentIndex;
            srcKeys.add(key.getBytes(StandardCharsets.UTF_8));
        }
        if (srcKeys.isEmpty()) {
            return 0;
        }

        RedisConnection connection = redisTemplate.getConnectionFactory().getConnection();
        try {
            String tmpKey = "TMP_AND-" + UUID.randomUUID();
            byte[] tmpKeyBytes = tmpKey.getBytes(StandardCharsets.UTF_8);
            connection.bitOp(RedisConnection.BitOperation.AND, tmpKeyBytes, srcKeys.toArray(new byte[0][]));
            Long cnt = connection.bitCount(tmpKeyBytes);
            connection.del(tmpKeyBytes);
            return cnt == null ? 0 : cnt.intValue();
        } finally {
            connection.close();
        }
    }

    private String buildTicketQueryCacheKey(DailyTrainTicketQueryReq req) {
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
