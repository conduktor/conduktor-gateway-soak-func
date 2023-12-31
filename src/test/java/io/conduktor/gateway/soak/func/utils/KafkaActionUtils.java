package io.conduktor.gateway.soak.func.utils;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.header.Header;
import org.assertj.core.api.Assertions;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ExecutionException;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * util class to make api call to kafka simpler
 */

@Slf4j
public class KafkaActionUtils {

    public static String quickCreateTopic(AdminClient adminClient, String topicName) throws InterruptedException {
        return createTopic(adminClient, topicName, 1, (short) 1, Map.of(), 10);
    }

    public static String createTopic(AdminClient adminClient, int partitions, short replicationFactor) throws InterruptedException {
        var topicName = "client_topic_" + UUID.randomUUID();
        return createTopic(adminClient, topicName, partitions, replicationFactor, Map.of(), 10);
    }

    public static String createTopic(AdminClient adminClient, String topicName, int partitions, short replicationFactor, Map<String, String> configs, int retryTimes) throws InterruptedException {
        try {
            adminClient.createTopics(Collections.singletonList(new NewTopic(topicName, partitions, replicationFactor).configs(configs)))
                    .all()
                    .get();
            var startTime = System.currentTimeMillis();
            long WAITING_TIMEOUT = 5000;
            boolean topicFound = false;
            while (System.currentTimeMillis() < startTime + WAITING_TIMEOUT && !topicFound) {
                topicFound = adminClient.listTopics()
                        .names()
                        .get().contains(topicName);
                if (!topicFound) {
                    Thread.sleep(100);
                }
            }
            if (!topicFound) {
                throw new RuntimeException("Topic " + topicName + " not created in " + WAITING_TIMEOUT + "ms");
            }
            return topicName;
        } catch (Exception exception) {
            if (exception.getCause() instanceof TimeoutException) {
                log.warn("kafka cluster is overloaded. Slow a little bit");
                Thread.sleep(200L);
                return createTopic(adminClient, topicName, partitions, replicationFactor, configs, retryTimes - 1);
            }
            throw new RuntimeException(exception);
        }
    }


    public static <T> void produce(KafkaProducer<String, T> kafkaProducer, String topic, String key, T value, List<Header> headers) throws ExecutionException, InterruptedException {
        var recordRequest = new ProducerRecord<>(topic, key, value);
        headers.forEach(header -> recordRequest.headers().add(header));
        var recordMetadata = kafkaProducer.send(recordRequest).get();
        Assertions.assertThat(recordMetadata.hasOffset()).isTrue();
    }

    public static <T> void produce(KafkaProducer<String, T> kafkaProducer, String topic, T value) throws ExecutionException, InterruptedException {
        produce(kafkaProducer, topic, "", value, 1);
    }

    public static <T> void produce(KafkaProducer<String, T> kafkaProducer, String topic, T value, int numOfRecords) throws ExecutionException, InterruptedException {
        produce(kafkaProducer, topic, "", value, numOfRecords);
    }

    public static <K, V> void produce(KafkaProducer<K, V> kafkaProducer, String topic, K key, V value, int numOfRecords) throws ExecutionException, InterruptedException {
        for (var i = 0; i < numOfRecords; i++) {
            var recordRequest = new ProducerRecord<>(topic, key, value);
            var recordMetadata = kafkaProducer.send(recordRequest).get();
            Assertions.assertThat(recordMetadata.hasOffset()).isTrue();
        }
    }

    public static <T> List<ConsumerRecord<String, T>> consume(KafkaConsumer<String, T> kafkaConsumer, String topic, int expectedRecords) {
        return consume(kafkaConsumer, topic, expectedRecords, 10000L);
    }

    public static <T> List<ConsumerRecord<String, T>> consume(KafkaConsumer<String, T> kafkaConsumer, String topic, int expectedRecords, long timeoutMs) {
        kafkaConsumer.assign(singletonList(new TopicPartition(topic, 0)));
        int recordCount = 0;
        long startTime = System.currentTimeMillis();
        var records = new ArrayList<ConsumerRecord<String, T>>();
        while (recordCount != expectedRecords && System.currentTimeMillis() < startTime + timeoutMs) {
            var consumedRecords = kafkaConsumer.poll(Duration.of(2, ChronoUnit.SECONDS));
            recordCount += consumedRecords.count();
            for (var record : consumedRecords) {
                records.add(record);
            }
        }
        assertThat(records).isNotNull();
        return records;
    }

}
