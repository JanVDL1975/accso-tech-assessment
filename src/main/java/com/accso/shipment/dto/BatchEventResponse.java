package com.accso.shipment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for batch event ingestion.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchEventResponse {

    private int totalReceived;
    private int acceptedCount;
    private int rejectedCount;
    private int duplicateCount;
    private List<EventIngestionResponse> results;

    public static BatchEventResponse of(List<EventIngestionResponse> results) {
        int accepted = 0;
        int rejected = 0;
        int duplicate = 0;
        for (EventIngestionResponse r : results) {
            if (r.isAccepted()) {
                accepted++;
            } else if ("DUPLICATE_EVENT".equals(r.getReason())) {
                duplicate++;
            } else {
                rejected++;
            }
        }
        return BatchEventResponse.builder()
                .totalReceived(results.size())
                .acceptedCount(accepted)
                .rejectedCount(rejected)
                .duplicateCount(duplicate)
                .results(results)
                .build();
    }
}
