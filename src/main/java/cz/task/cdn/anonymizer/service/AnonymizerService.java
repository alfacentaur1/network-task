package cz.task.cdn.anonymizer.service;

import cz.task.cdn.anonymizer.model.HttpLog;
import cz.task.cdn.anonymizer.model.RecordAck;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.capnproto.ArrayInputStream;
import org.capnproto.MessageReader;
import org.capnproto.Serialize;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.nio.ByteBuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
@Slf4j
@RequiredArgsConstructor
public class AnonymizerService {

    private final Queue<RecordAck> queue = new ConcurrentLinkedQueue<>();
    private final RestTemplate restTemplate = new RestTemplate();

    //listerner for topic http_log, groupId data-engineering-task-reader
    @KafkaListener(topics = "http_log", groupId = "data-engineering-task-reader")
    public void listen(byte[] message, Acknowledgment ack) {
        //add to queue for batch processing
        queue.add(new RecordAck(message, ack));
    }

    //every 65 seconds, process batch of messages and send to clickhouse, then acknowledge
    //initial 70, bcs database queried first, we need to have time buffer
    @Scheduled(fixedRate = 65000)
    public void processBatch() {
        if (queue.isEmpty()) return;

        List<RecordAck> batch = new ArrayList<>();
        while (!queue.isEmpty()) {
            batch.add(queue.poll());
        }

        try {
            StringBuilder sb = new StringBuilder();
            for (RecordAck item : batch) {
                //deserialize capnproto to object, extract fields, anonymize ip, build JSONEachRow string
                MessageReader messageReader = Serialize.read(new ArrayInputStream(ByteBuffer.wrap(item.getRawMessage())));
                HttpLog.HttpLogRecord.Reader r = messageReader.getRoot(HttpLog.HttpLogRecord.factory);
                //replace last octet of ip with X for anonymization
                String rawIp = r.getRemoteAddr().toString();
                int lastDot = rawIp.lastIndexOf('.');
                String anonymizedIp = lastDot > 0 ? rawIp.substring(0, lastDot + 1) + "X" : rawIp;

                //build JSONEachRow string for ClickHouse
                sb.append(String.format(
                        "{\"timestamp\":%d,\"resource_id\":%d,\"bytes_sent\":%d,\"request_time\":%d,\"response_status\":%d,\"cache_status\":\"%s\",\"method\":\"%s\",\"remote_addr\":\"%s\",\"url\":\"%s\"}\n",
                        r.getTimestampEpochMilli(), r.getResourceId(), r.getBytesSent(), r.getRequestTimeMilli(),
                        r.getResponseStatus(), r.getCacheStatus(), r.getMethod(), anonymizedIp, r.getUrl()
                ));
            }
            //clickhouse expects application/json and each row as a separate line for JSONEachRow format
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-ClickHouse-User", "default");
            headers.set("X-ClickHouse-Key", "password123");
            //set by parameters to url
            String url = "http://localhost:8124/?query=INSERT INTO http_logs FORMAT JSONEachRow";
            //send it as resttemplate post request to nginx
            HttpEntity<String> entity = new HttpEntity<>(sb.toString(), headers);
            restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    String.class
            );
            batch.forEach(item -> item.getAck().acknowledge());
            log.info("Batch sent(RestTemplate): {} records.", batch.size());

        } catch (Exception e) {
            log.error("Error while sending!", e);
            queue.addAll(batch);
        }
    }
}