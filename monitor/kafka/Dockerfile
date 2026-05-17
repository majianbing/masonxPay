ARG KAFKA_IMAGE=apache/kafka:4.1.2
ARG JMX_EXPORTER_VERSION=1.0.1

FROM maven:3.9-eclipse-temurin-21 AS jmx-exporter
ARG JMX_EXPORTER_VERSION
RUN mvn -q org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy \
    -Dartifact=io.prometheus.jmx:jmx_prometheus_javaagent:${JMX_EXPORTER_VERSION} \
    -DoutputDirectory=/tmp \
    -Dmdep.stripVersion=true

FROM ${KAFKA_IMAGE}
COPY --from=jmx-exporter /tmp/jmx_prometheus_javaagent.jar /opt/kafka/libs/jmx_prometheus_javaagent.jar
COPY kafka-jmx.yml /opt/kafka/config/kafka-jmx.yml
