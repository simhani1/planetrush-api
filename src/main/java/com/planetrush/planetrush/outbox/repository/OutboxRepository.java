package com.planetrush.planetrush.outbox.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.planetrush.planetrush.outbox.domain.OutboxEvent;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {
}
