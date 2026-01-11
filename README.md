# Basic CP docker example with Prometheus/Grafana Monitoring 

Just a basic example for easy prototyping of CP installation with monitoring through Prometheus and Grafana.

- [Basic CP docker example with Prometheus/Grafana Monitoring](#basic-cp-docker-example-with-prometheusgrafana-monitoring)
  - [Disclaimer](#disclaimer)
  - [Setup](#setup)
    - [Start Docker Compose](#start-docker-compose)
    - [Grafana/Prometheus](#grafanaprometheus)
      - [Client Dashboard](#client-dashboard)
      - [Boker Dashboard](#boker-dashboard)
  - [How it works](#how-it-works)
    - [What is running](#what-is-running)
    - [CP Broker](#cp-broker)
    - [Producer/Consumer Client](#producerconsumer-client)
    - [Prometheus](#prometheus)
    - [Grafana](#grafana)
  - [Kafka Lag Exporter](#kafka-lag-exporter)
  - [Cleanup](#cleanup)

## Disclaimer

The code and/or instructions here available are **NOT** intended for production usage. 
It's only meant to serve as an example or reference and does not replace the need to follow actual and official documentation of referenced products.

## Setup

### Start Docker Compose

```bash
docker compose up -d
```

### Grafana/Prometheus

The grafana/prometheus is already setup including dashboard available at http://localhost:3000 with user/password: `admin`/`grafana`.

Both for the server/broker (check dashboard `Confluent Platform`) as for the clients (check dashboard `Kafka Clients (Producer & Consumer)`).

#### Client Dashboard

We can see charts for:
- Consumer Records Consumed per Second (basically uses `sum(rate(kafka_consumer_records_consumed_total[1m]))` - average per-second increase of the counter, calculated over the last 1 minute window)
- Consumer Bytes Consumed/sec (`sum(rate(kafka_consumer_bytes_consumed_rate[1m]))`)
- Producer Records Sent/sec (`sum(rate(kafka_producer_record_send_total[1m]))`)
- Producer Bytes Sent/sec (`sum(rate(kafka_producer_outgoing_byte_rate[1m]))`)
- Consumer Lag (max) (`max(kafka_consumer_records_lag_max)`)

There are also charts for the Memory and CPU usages.

#### Boker Dashboard

For the CP broker/controller side we also have some associated charts:

- Total Incoming Byte Rate
- Total Messages In Per Second
- Total Outgoing Byte Rate
- Incoming Byte Rate
- Messages In Per Second
- Outgoing Byte Rate
- Total Fetch Request Rate
- Fetch Request Rate
- Total Produce Request Rate
- Produce Request Rate

Note that it should also be possible to explore most of the client metrics from the broker side with CP 8+ with [KIP-714](https://cwiki.apache.org/confluence/display/KAFKA/KIP-714%3A+Client+metrics+and+observability). Check https://github.com/rjmfernandes/kip714

## How it works

### What is running

If we look into [compose.yml](./compose.yml) we see the following services:
- CP broker (and controller) instance
- Prometheus
- Grafana
- A client that is producing and consuming to/from the same topic. 

### CP Broker

To expose the JMX port for Prometheus to scrape we set:

```
      KAFKA_JMX_PORT: 9999
      KAFKA_JMX_HOSTNAME: broker
      KAFKA_OPTS: "-javaagent:/etc/kafka/${JMX}=7071:/etc/kafka/kafka-metrics.yml"
```

The variable `JMX` is pointing as per [.env](./.env) file to the library (we have mounted in the `compose.yml` file from `kafka/jmx_prometheus_javaagent-0.20.0.jar`):

```
JMX=jmx_prometheus_javaagent-0.20.0.jar
```

We are also setting the port for Prometheus to screape to `7071`.

And finally we are defining the metrics to be exposed with the file [kafka-metrics.yml](./kafka/kafka-metrics.yml).

### Producer/Consumer Client

We are running the producer and the consumer from the same Java class [ProducerConsumerDemo.java](./producerConsumer/src/main/java/producerconsumer/ProducerConsumerDemo.java) in different threads.

This class is a minimal Kafka producer–consumer demo designed specifically to intentionally generate observable consumer lag at the Kafka client level, so metrics like `kafka_consumer_records_lag_max` can be visualised in Grafana.

When defining the service in the [compose.yml](./compose.yml) we expose the JMX (on port 7074):

```
    command:
      [
        "java",
        "-javaagent:/opt/jmx/${JMX}=7074:/app/jmx-exporter.yml",
        "-jar",
        "/app/app.jar",
      ]
```

The library was mounted in the service just like before with the CP broker.

### Prometheus

We define the tragets for scraping in the file [prometheus.yml](./prometheus/prometheus.yml). So that it will scrape both the CP broker and the client.

### Grafana

We define first the data source pointing to our prometheus in [datasource.yml](./grafana/datasource/datasource.yml).

Next we deine the dashboards (mounted in the service in the `compose.yml`):
- [main-dashboard.json](./grafana/dashboards/main-dashboard.json)
- [client-dashboard.json](./grafana/dashboards/client-dashboard.json)

In general you will want to explore the metrics in Prometheus check for example in http://localhost:9090 to query `kafka_consumer_` and see the metrics available for you to build the charts in Grafana and save the dashboard after for reuse.

## Kafka Lag Exporter

In versions previous to CP 8 you can access the consumer group log through the broker leveraging tools like [Kafka Lag Exporter](https://github.com/seglo/kafka-lag-exporter). 

Here we are running it as one service more in our [compose.yml](./compose.yml) and we configure it with [application.conf](kafka-lag-exporter/application.conf):

```
kafka-lag-exporter {
  reporters.prometheus.port = 8000
  clusters = [
    {
      name = "local"
      bootstrap-brokers = "broker:19092"
    }
  ]
}
```

So that as part of our [prometheus.yml](prometheus/prometheus.yml) configuration we also can configure the scrape for the kafka-lag-exporter:

```
  - job_name: kafka-lag-exporter
    metrics_path: /metrics
    scrape_interval: 15s
    static_configs:
      - targets:
          - "kafka-lag-exporter:8000"
        labels:
          namespace: "docker"
          cluster_name: "local"
```

In Grafana we have another dashboard set [Kafka Lag Exporter](grafana/dashboards/Kafka_Lag_Exporter_Dashboard.json).

Check this [article](https://seanglover.com/assets/Monitor%20Kafka%20Consumer%20Group%20Latency%20with%20Kafka%20Lag%20Exporter%20_%20Lightbend.pdf) from Sean Glover for more information on Kafka Lag Exporter. But remember that on more recent versions of CP (8+) you shouldn't need it. 

## Cleanup

```shell
docker compose down -v
```


