package com.rootcauseplatform.ingestion.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Creates the error-event topic at startup if it does not exist yet. */
@Configuration(proxyBeanMethods = false)
public class KafkaTopicConfiguration {

    @Bean
    NewTopic errorEventsTopic(KafkaTopicProperties topics) {
        return TopicBuilder.name(topics.errorEvents()).partitions(topics.partitions()).build();
    }
}
