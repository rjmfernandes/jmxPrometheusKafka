# Basic CP docker example with Prometheus/Grafana Monitoring 

Just a basic example for easy prototyping of CP installation with monitoring through Prometheus and Grafana.

- [Basic CP docker example with Prometheus/Grafana Monitoring](#basic-cp-docker-example-with-prometheusgrafana-monitoring)
  - [Disclaimer](#disclaimer)
  - [Setup](#setup)
    - [Start Docker Compose](#start-docker-compose)
    - [Grafana/Prometheus](#grafanaprometheus)
    - [Build producerConsumer jar](#build-producerconsumer-jar)

## Disclaimer

The code and/or instructions here available are **NOT** intended for production usage. 
It's only meant to serve as an example or reference and does not replace the need to follow actual and official documentation of referenced products.

## Setup

### Start Docker Compose

```bash
docker compose up -d
```

### Grafana/Prometheus

The grafana/prometheus is already setup including dashboard available at http://localhost:3000 with user/password: admin/grafana.

### Build producerConsumer jar

```shell
cd producerConsumer
mvn package
cd ..
```
