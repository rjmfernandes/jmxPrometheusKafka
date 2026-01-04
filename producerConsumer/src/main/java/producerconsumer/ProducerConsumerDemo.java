package producerconsumer;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Minimal standalone producer + consumer demo against the configured Kafka cluster.
 * Adjust bootstrap, topic, and throughput via env vars if desired.
 */
public class ProducerConsumerDemo {
    private static final String DEFAULT_BOOTSTRAP = System.getenv().getOrDefault("KAFKA_BOOTSTRAP", "localhost:9091");
    private static final String TOPIC = System.getenv().getOrDefault("KAFKA_TOPIC", "demo-topic");
    private static final long PRODUCE_INTERVAL_MS = Long.parseLong(System.getenv().getOrDefault("PRODUCE_INTERVAL_MS", "1000"));

    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<?> producerFuture = pool.submit(() -> runProducer(DEFAULT_BOOTSTRAP, TOPIC));
        Future<?> consumerFuture = pool.submit(() -> runConsumer(DEFAULT_BOOTSTRAP, TOPIC));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            pool.shutdownNow();
            try {
                pool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        }));

        producerFuture.get();
        consumerFuture.get();
    }

    private static void runProducer(String bootstrap, String topic) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "50");
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "demo-producer-" + UUID.randomUUID());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            long i = 0;
            while (!Thread.currentThread().isInterrupted()) {
                String key = "key-" + (i % 10);
                String value = "demo-message-" + i;
                try {
                    RecordMetadata md = producer.send(new ProducerRecord<>(topic, key, value)).get();
                    System.out.printf("Produced to %s/%d@%d: %s -> %s%n", md.topic(), md.partition(), md.offset(), key, value);
                } catch (Exception e) {
                    System.err.println("Producer send failed: " + e.getMessage());
                }
                i++;
                sleepQuietly(PRODUCE_INTERVAL_MS);
            }
        }
    }

    private static void runConsumer(String bootstrap, String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, System.getenv().getOrDefault("KAFKA_CONSUMER_GROUP", "demo-group"));
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "demo-consumer-" + UUID.randomUUID());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(topic));
            while (!Thread.currentThread().isInterrupted()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
                for (ConsumerRecord<String, String> r : records) {
                    System.out.printf("Consumed from %s/%d@%d: %s -> %s%n", r.topic(), r.partition(), r.offset(), r.key(), r.value());
                }
            }
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
