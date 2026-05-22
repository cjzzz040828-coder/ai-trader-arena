package com.aitrade.gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class SnapshotResponse {
    @JsonProperty("market_status")
    private String marketStatus;

    @JsonProperty("last_ok_ago_seconds")
    private Double lastOkAgoSeconds;

    private Integer count;

    private List<Map<String, Object>> data;
}
