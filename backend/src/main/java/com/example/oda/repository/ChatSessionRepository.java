package com.example.oda.repository;

import com.example.oda.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {
    List<ChatSession> findByUserEmailOrderByCreatedAtDesc(String userEmail);
}
