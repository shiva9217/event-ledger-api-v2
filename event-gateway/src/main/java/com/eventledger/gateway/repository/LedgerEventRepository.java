package com.eventledger.gateway.repository;

import com.eventledger.gateway.domain.LedgerEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LedgerEventRepository extends JpaRepository<LedgerEvent, Long> {

    Optional<LedgerEvent> findByEventId(String eventId);

    boolean existsByEventId(String eventId);

    /** Always chronological by the original business timestamp, never arrival order. */
    List<LedgerEvent> findByAccountIdOrderByEventTimestampAsc(String accountId);
}
