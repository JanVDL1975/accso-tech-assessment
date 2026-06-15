package com.accso.shipment.controller;

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

        // First event: LABEL_CREATED
        ShipmentEventRequest evt1 = ShipmentEventRequest.builder()
                .eventId("evt-hist-1")
                .partner("dhl")
                .shipmentId(shipmentId)
                .status(ShipmentStatus.LABEL_CREATED)
                .occurredAt(Instant.parse("2026-06-13T08:00:00Z"))
                .receivedAt(Instant.parse("2026-06-13T08:00:00Z"))
                .build();

        // Second event: HANDED_TO_CARRIER (valid transition)
        ShipmentEventRequest evt2 = ShipmentEventRequest.builder()
                .eventId("evt-hist-2")
                .partner("dhl")
                .shipmentId(shipmentId)
                .status(ShipmentStatus.HANDED_TO_CARRIER)
                .occurredAt(Instant.parse("2026-06-13T09:00:00Z"))
                .receivedAt(Instant.parse("2026-06-13T09:00:00Z"))
                .build();

        // Third event: DELIVERED (invalid transition from HANDED_TO_CARRIER - rejected)
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

        // Retrieve history
        MvcResult result = mockMvc.perform(get("/api/v1/shipments/" + shipmentId + "/events"))
                .andExpect(status().isOk())
                .andReturn();

        List<?> events = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, Object.class));

        assertEquals(2, events.size());

        // Only accepted events in derived_events, ordered by receivedAt
        assertEquals("evt-hist-1", ((java.util.Map) events.get(0)).get("eventId"));
        assertEquals("LABEL_CREATED", ((java.util.Map) events.get(0)).get("status"));

        assertEquals("evt-hist-2", ((java.util.Map) events.get(1)).get("eventId"));
        assertEquals("HANDED_TO_CARRIER", ((java.util.Map) events.get(1)).get("status"));

        // Note: evt-hist-3 (DELIVERED) was rejected and is NOT stored.
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
}
