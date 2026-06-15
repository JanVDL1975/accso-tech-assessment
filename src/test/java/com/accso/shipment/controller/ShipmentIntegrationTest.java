package com.accso.shipment.controller;

import com.accso.shipment.dto.BatchEventRequest;
import com.accso.shipment.dto.EventIngestionResponse;
import com.accso.shipment.dto.ShipmentEventRequest;
import com.accso.shipment.domain.ShipmentStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ShipmentIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void receiveEvent_acceptsValidEvent() throws Exception {
        String eventId = "evt-" + System.nanoTime();
        ShipmentEventRequest request = ShipmentEventRequest.builder()
                .eventId(eventId)
                .partner("dhl")
                .shipmentId("ship-1")
                .status(ShipmentStatus.LABEL_CREATED)
                .occurredAt(Instant.now())
                .receivedAt(Instant.now())
                .location("Amsterdam")
                .build();

        mockMvc.perform(post("/api/v1/shipments/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.eventId").value(eventId));
    }

    @Test
    void receiveEvent_rejectsDuplicate() throws Exception {
        String eventId = "evt-dup";
        ShipmentEventRequest request = ShipmentEventRequest.builder()
                .eventId(eventId)
                .partner("dhl")
                .shipmentId("ship-1")
                .status(ShipmentStatus.LABEL_CREATED)
                .occurredAt(Instant.now())
                .receivedAt(Instant.now())
                .location("Amsterdam")
                .build();

        mockMvc.perform(post("/api/v1/shipments/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));

        mockMvc.perform(post("/api/v1/shipments/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.reason").value("DUPLICATE_EVENT"));
    }

    @Test
    void getStatus_returnsNotFound_forUnknownShipment() throws Exception {
        mockMvc.perform(get("/api/v1/shipments/unknown-shipment/status"))
                .andExpect(status().isNotFound());
    }

    @Test
    void receiveEvent_updatesCurrentState() throws Exception {
        String eventId = "evt-" + System.nanoTime();
        ShipmentEventRequest request = ShipmentEventRequest.builder()
                .eventId(eventId)
                .partner("dhl")
                .shipmentId("ship-status-test")
                .status(ShipmentStatus.LABEL_CREATED)
                .occurredAt(Instant.now())
                .receivedAt(Instant.now())
                .location("Berlin")
                .build();

        mockMvc.perform(post("/api/v1/shipments/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));

        mockMvc.perform(get("/api/v1/shipments/ship-status-test/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipmentId").value("ship-status-test"))
                .andExpect(jsonPath("$.currentStatus").value("LABEL_CREATED"))
                .andExpect(jsonPath("$.location").value("Berlin"));
    }

    @Test
    void health_returnsUp() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void getEventHistory_returnsEventsOrderedByReceivedAt() throws Exception {
        String shipmentId = "ship-history-" + System.nanoTime();

        ShipmentEventRequest evt1 = ShipmentEventRequest.builder()
                .eventId("evt-hist-1")
                .partner("dhl")
                .shipmentId(shipmentId)
                .status(ShipmentStatus.LABEL_CREATED)
                .occurredAt(Instant.parse("2026-06-13T08:00:00Z"))
                .receivedAt(Instant.parse("2026-06-13T08:00:00Z"))
                .build();

        ShipmentEventRequest evt2 = ShipmentEventRequest.builder()
                .eventId("evt-hist-2")
                .partner("dhl")
                .shipmentId(shipmentId)
                .status(ShipmentStatus.HANDED_TO_CARRIER)
                .occurredAt(Instant.parse("2026-06-13T09:00:00Z"))
                .receivedAt(Instant.parse("2026-06-13T09:00:00Z"))
                .build();

        ShipmentEventRequest evt3 = ShipmentEventRequest.builder()
                .eventId("evt-hist-3")
                .partner("dhl")
                .shipmentId(shipmentId)
                .status(ShipmentStatus.DELIVERED)
                .occurredAt(Instant.parse("2026-06-13T10:00:00Z"))
                .receivedAt(Instant.parse("2026-06-13T10:00:00Z"))
                .build();

        mockMvc.perform(post("/api/v1/shipments/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(evt1)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/shipments/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(evt2)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/shipments/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(evt3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false));

        MvcResult result = mockMvc.perform(get("/api/v1/shipments/" + shipmentId + "/events"))
                .andExpect(status().isOk())
                .andReturn();

        List<?> events = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, Object.class));

        assertEquals(2, events.size());
        assertEquals("evt-hist-1", ((java.util.Map) events.get(0)).get("eventId"));
        assertEquals("LABEL_CREATED", ((java.util.Map) events.get(0)).get("status"));
        assertEquals("evt-hist-2", ((java.util.Map) events.get(1)).get("eventId"));
        assertEquals("HANDED_TO_CARRIER", ((java.util.Map) events.get(1)).get("status"));
    }

    @Test
    void getEventHistory_returnsEmptyList_forUnknownShipment() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/shipments/unknown-ship/events"))
                .andExpect(status().isOk())
                .andReturn();

        List<?> events = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, Object.class));

        assertTrue(events.isEmpty());
    }

    @Test
    void receiveBatchEvents_processesMultipleEvents() throws Exception {
        List<ShipmentEventRequest> events = List.of(
                createEventRequest("batch-evt-1", "ship-batch-1", ShipmentStatus.LABEL_CREATED),
                createEventRequest("batch-evt-2", "ship-batch-1", ShipmentStatus.HANDED_TO_CARRIER)
        );
        BatchEventRequest batchRequest = BatchEventRequest.builder().events(events).build();

        mockMvc.perform(post("/api/v1/shipments/events/list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batchRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalReceived").value(2))
                .andExpect(jsonPath("$.acceptedCount").value(2))
                .andExpect(jsonPath("$.rejectedCount").value(0));
    }

    @Test
    void receiveBatchEvents_handlesDuplicatesWithinBatch() throws Exception {
        List<ShipmentEventRequest> events = List.of(
                createEventRequest("batch-dup-1", "ship-batch-dup", ShipmentStatus.LABEL_CREATED),
                createEventRequest("batch-dup-1", "ship-batch-dup", ShipmentStatus.LABEL_CREATED)
        );
        BatchEventRequest batchRequest = BatchEventRequest.builder().events(events).build();

        mockMvc.perform(post("/api/v1/shipments/events/list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batchRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalReceived").value(2))
                .andExpect(jsonPath("$.acceptedCount").value(1))
                .andExpect(jsonPath("$.duplicateCount").value(1));
    }

    @Test
    void receiveBatchEvents_atomicPerEvent() throws Exception {
        List<ShipmentEventRequest> events = List.of(
                createEventRequest("batch-atomic-1", "ship-batch-atomic", ShipmentStatus.LABEL_CREATED),
                createEventRequest("batch-atomic-2", "ship-batch-atomic", ShipmentStatus.DELIVERED)
        );
        BatchEventRequest batchRequest = BatchEventRequest.builder().events(events).build();

        mockMvc.perform(post("/api/v1/shipments/events/list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batchRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalReceived").value(2))
                .andExpect(jsonPath("$.acceptedCount").value(1))
                .andExpect(jsonPath("$.rejectedCount").value(1));
    }

    @Test
    void getAuditLog_returnsDecisionHistory() throws Exception {
        String shipmentId = "ship-audit-" + System.nanoTime();

        ShipmentEventRequest evt1 = ShipmentEventRequest.builder()
                .eventId("audit-evt-1")
                .partner("dhl")
                .shipmentId(shipmentId)
                .status(ShipmentStatus.LABEL_CREATED)
                .occurredAt(Instant.parse("2026-06-15T08:00:00Z"))
                .receivedAt(Instant.parse("2026-06-15T08:00:00Z"))
                .build();

        ShipmentEventRequest evt2 = ShipmentEventRequest.builder()
                .eventId("audit-evt-2")
                .partner("dhl")
                .shipmentId(shipmentId)
                .status(ShipmentStatus.HANDED_TO_CARRIER)
                .occurredAt(Instant.parse("2026-06-15T09:00:00Z"))
                .receivedAt(Instant.parse("2026-06-15T09:00:00Z"))
                .build();

        mockMvc.perform(post("/api/v1/shipments/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(evt1)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/shipments/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(evt2)))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/api/v1/shipments/" + shipmentId + "/audit"))
                .andExpect(status().isOk())
                .andReturn();

        List<?> entries = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, Object.class));

        assertEquals(2, entries.size());
        assertEquals("ACCEPTED", ((java.util.Map) entries.get(0)).get("decision"));
        assertEquals("ACCEPTED", ((java.util.Map) entries.get(1)).get("decision"));
    }

    private ShipmentEventRequest createEventRequest(String eventId, String shipmentId, ShipmentStatus status) {
        return ShipmentEventRequest.builder()
                .eventId(eventId)
                .partner("dhl")
                .shipmentId(shipmentId)
                .status(status)
                .occurredAt(Instant.now())
                .receivedAt(Instant.now())
                .location("TestLocation")
                .build();
    }
}
