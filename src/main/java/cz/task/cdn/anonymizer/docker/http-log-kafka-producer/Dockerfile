FROM amazoncorretto:17.0.3-alpine3.15

ENV KAFKA_BOOTSTRAP_SERVERS="localhost:9092"
ENV KAFKA_PRODUCER_DELAY_MS=500

COPY httploggen-1.0.0-jar-with-dependencies.jar /home/httploggen-1.0.0-jar-with-dependencies.jar
COPY entrypoint.sh entrypoint.sh

ENTRYPOINT ["/bin/sh", "entrypoint.sh"]
