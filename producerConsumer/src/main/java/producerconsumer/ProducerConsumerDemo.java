package producerconsumer;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.ThreadLocalRandom;

public class ProducerConsumerDemo {
    private static final String DEFAULT_BOOTSTRAP = System.getenv().getOrDefault("KAFKA_BOOTSTRAP", "localhost:9091");
    private static final String TOPIC = System.getenv().getOrDefault("KAFKA_TOPIC", "demo-topic");

    // Make producer faster so lag can build easily
    private static final long PRODUCE_INTERVAL_MS = Long
            .parseLong(System.getenv().getOrDefault("PRODUCE_INTERVAL_MS", "20"));

    // Consumer: only fetch 1-2 records per poll
    private static final int MAX_POLL_RECORDS = Integer.parseInt(System.getenv().getOrDefault("MAX_POLL_RECORDS", "2"));

    // Slow processing so consumption throughput < production throughput
    private static final long PROCESS_DELAY_MIN_MS = Long
            .parseLong(System.getenv().getOrDefault("PROCESS_DELAY_MIN_MS", "400"));
    private static final long PROCESS_DELAY_MAX_MS = Long
            .parseLong(System.getenv().getOrDefault("PROCESS_DELAY_MAX_MS", "2000"));
    private static final double PROCESS_DELAY_PROB = Double
            .parseDouble(System.getenv().getOrDefault("PROCESS_DELAY_PROB", "0.85"));

    // Keep polling alive even if we process slowly
    private static final int MAX_POLL_INTERVAL_MS = Integer
            .parseInt(System.getenv().getOrDefault("MAX_POLL_INTERVAL_MS", "900000")); // 15 min

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
        props.put(ProducerConfig.LINGER_MS_CONFIG, "20");
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "demo-producer-" + UUID.randomUUID());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            long i = 0;
            while (!Thread.currentThread().isInterrupted()) {
                String key = "key-" + (i % 10);
                String value = "demo-message-" + i;

                try {
                    RecordMetadata md = producer.send(new ProducerRecord<>(topic, key, value)).get();
                    System.out.printf("Produced %s/%d@%d: %s -> %s%n",
                            md.topic(), md.partition(), md.offset(), key, value);
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

        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, String.valueOf(MAX_POLL_RECORDS));
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, String.valueOf(MAX_POLL_INTERVAL_MS));

        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(topic));

            long lastPrint = 0;

            while (!Thread.currentThread().isInterrupted()) {
                // Poll frequently so fetch metrics update (including records-lag-max)
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));

                for (ConsumerRecord<String, String> r : records) {
                    System.out.printf("Consumed %s/%d@%d: %s -> %s%n",
                            r.topic(), r.partition(), r.offset(), r.key(), r.value());

                    // Random slow processing per record (this is what makes position advance
                    // slowly)
                    if (rnd.nextDouble() < PROCESS_DELAY_PROB) {
                        long delayMs = rnd.nextLong(PROCESS_DELAY_MIN_MS, PROCESS_DELAY_MAX_MS + 1);
                        sleepQuietly(delayMs);
                    }
                }

                // Optional: print "client fetch lag" locally as endOffset - position (every 5s)
                long now = System.currentTimeMillis();
                if (now - lastPrint > 5000) {
                    lastPrint = now;
                    Set<TopicPartition> assigned = consumer.assignment();
                    if (!assigned.isEmpty()) {
                        Map<TopicPartition, Long> end = consumer.endOffsets(assigned);
                        long totalLag = 0;
                        for (TopicPartition tp : assigned) {
                            long pos = consumer.position(tp);
                            long e = end.getOrDefault(tp, pos);
                            long lag = Math.max(0, e - pos);
                            totalLag += lag;
                            System.out.printf(">>> %s end=%d pos=%d lag=%d%n", tp, e, pos, lag);
                        }
                        System.out.printf(">>> TOTAL end-pos lag = %d%n", totalLag);
                    }
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
