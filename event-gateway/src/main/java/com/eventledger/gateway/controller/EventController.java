package com.eventledger.gateway.controller;

import com.eventledger.gateway.dto.CreateEventResult;
import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.exception.ServiceUnavailableException;
import com.eventledger.gateway.service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
public class EventController {

    private static final String IDEMPOTENCY_HEADER = "X-Idempotency-Status";

    private final EventService eventService;

    @PostMapping
    public ResponseEntity<EventResponse> create(@Valid @RequestBody EventRequest request) {
        CreateEventResult result = eventService.ingest(request);

        return switch (result.outcome()) {
            case DUPLICATE -> ResponseEntity.ok()
                    .header(IDEMPOTENCY_HEADER, "DUPLICATE")
                    .body(result.event());
            case ACCEPTED -> ResponseEntity.status(HttpStatus.CREATED).body(result.event());
            case FAILED -> throw new ServiceUnavailableException(
                    "Account service is unavailable; event " + result.event().eventId()
                            + " was recorded with status FAILED", null);
        };
    }

    /** Read path — never touches the circuit-breaker-guarded account-service call. */
    @GetMapping("/{id}")
    public ResponseEntity<EventResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(eventService.getById(id));
    }

    /** Read path — works even when account-service is down. */
    @GetMapping
    public ResponseEntity<List<EventResponse>> getByAccount(@RequestParam("account") String accountId) {
        return ResponseEntity.ok(eventService.getByAccount(accountId));
    }
}
