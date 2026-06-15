package com.accso.shipment.service;

import com.accso.shipment.domain.ShipmentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of state resolution for an incoming event.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentResolutionResult {

    private boolean accepted;
    private ShipmentStatus newStatus;
    private java.time.Instant newLastOccurredAt;
    private java.time.Instant newLastReceivedAt;
    private String newLocation;
    private String rejectionReason;

    public static ShipmentResolutionResult accepted(ShipmentStatus status, java.time.Instant occurredAt, java.time.Instant receivedAt, String location) {
        return ShipmentResolutionResult.builder()
                .accepted(true)
                .newStatus(status)
                .newLastOccurredAt(occurredAt)
                .newLastReceivedAt(receivedAt)
                .newLocation(location)
                .build();
    }

    public static ShipmentResolutionResult noUpdate() {
        return ShipmentResolutionResult.builder()
                .accepted(true)
                .build();
    }

    public static ShipmentResolutionResult rejected(String reason) {
        return ShipmentResolutionResult.builder()
                .accepted(false)
                .rejectionReason(reason)
                .build();
    }

    public boolean isAccepted() {
        return accepted;
    }

    public ShipmentStatus getNewStatus() {
        return newStatus;
    }

    public java.time.Instant getNewLastReceivedAt() {
        return newLastReceivedAt;
    }

    public java.time.Instant getNewLastOccurredAt() {
        return newLastOccurredAt;
    }

    public String getNewLocation() {
        return newLocation;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }
}
