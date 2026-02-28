package cz.task.cdn.anonymizer.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogRecord {
    private Long timestamp;

    @JsonProperty("resource_id")
    private Long resourceId;

    @JsonProperty("bytes_sent")
    private Long bytesSent;

    @JsonProperty("request_time")
    private Long requestTime;

    @JsonProperty("response_status")
    private Integer responseStatus;

    @JsonProperty("cache_status")
    private String cacheStatus;

    private String method;

    @JsonProperty("remote_addr")
    private String remoteAddr;

    private String url;
}
