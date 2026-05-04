package com.jiawa.train.business.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    public static final String CONFIRM_ORDER_EXCHANGE = "train.confirm.order.exchange";
    public static final String CONFIRM_ORDER_QUEUE = "train.confirm.order.queue";
    public static final String CONFIRM_ORDER_ROUTING_KEY = "train.confirm.order";

    @Bean
    public DirectExchange confirmOrderExchange() {
        return new DirectExchange(CONFIRM_ORDER_EXCHANGE, true, false);
    }

    @Bean
    public Queue confirmOrderQueue() {
        return new Queue(CONFIRM_ORDER_QUEUE, true);
    }

    @Bean
    public Binding confirmOrderBinding(Queue confirmOrderQueue, DirectExchange confirmOrderExchange) {
        return BindingBuilder.bind(confirmOrderQueue).to(confirmOrderExchange).with(CONFIRM_ORDER_ROUTING_KEY);
    }

    @Bean
    public Jackson2JsonMessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, Jackson2JsonMessageConverter converter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(converter);
        return rabbitTemplate;
    }
}

