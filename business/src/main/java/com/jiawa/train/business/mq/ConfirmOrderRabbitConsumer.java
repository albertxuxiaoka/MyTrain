package com.jiawa.train.business.mq;

import com.jiawa.train.business.config.RabbitMqConfig;
import com.jiawa.train.business.dto.ConfirmOrderMQDto;
import com.jiawa.train.business.service.AfterConfirmOrderService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ConfirmOrderRabbitConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(ConfirmOrderRabbitConsumer.class);

    @Resource
    private AfterConfirmOrderService afterConfirmOrderService;

    @RabbitListener(queues = RabbitMqConfig.CONFIRM_ORDER_QUEUE)
    public void consume(ConfirmOrderMQDto dto) {
        LOG.info("RabbitMQ消费确认订单：{}", dto);
        afterConfirmOrderService.afterDoConfirmAsync(dto);
    }
}

