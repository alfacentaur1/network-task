package cz.task.cdn.anonymizer.model;

import org.springframework.kafka.support.Acknowledgment;
import lombok.Value;

@Value
public class RecordAck {
    byte[] rawMessage;
    Acknowledgment ack;
}