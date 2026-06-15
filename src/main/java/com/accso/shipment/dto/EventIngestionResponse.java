package com.accso.shipment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for event ingestion.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventIngestionResponse {

    private boolean accepted;
    private String eventId;
    private String reason;

    public static EventIngestionResponse accepted(String eventId) {
        return EventIngestionResponse.builder()
                .accepted(true)
                .eventId(eventId)
                .build();
    }

    public static EventIngestionResponse duplicate(String eventId) {
        return EventIngestionResponse.builder()
                .accepted(false)
                .eventId(eventId)
                .reason("DUPLICATE_EVENT")
                .build();
    }

    public static EventIngestionResponse rejected(String eventId, String reason) {
        return EventIngestionResponse.builder()
                .accepted(false)
                .eventId(eventId)
                .reason(reason)
                .build();
    }
}
