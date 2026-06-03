package com.eventledger.gateway.controller;

import com.eventledger.gateway.dto.HealthResponse;
import com.eventledger.gateway.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class HealthController {

    private final EventService eventService;

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        boolean dbUp = eventService.isDatabaseUp();
        HealthResponse body = new HealthResponse(
                dbUp ? "UP" : "DOWN", "event-gateway", dbUp ? "UP" : "DOWN");
        return ResponseEntity.status(dbUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
