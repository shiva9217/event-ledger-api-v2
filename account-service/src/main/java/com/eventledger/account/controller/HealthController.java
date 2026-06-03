package com.eventledger.account.controller;

import com.eventledger.account.dto.HealthResponse;
import com.eventledger.account.service.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class HealthController {

    private final AccountService accountService;

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        boolean dbUp = accountService.isDatabaseUp();
        HealthResponse body = new HealthResponse(
                dbUp ? "UP" : "DOWN", "account-service", dbUp ? "UP" : "DOWN");
        return ResponseEntity.status(dbUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
