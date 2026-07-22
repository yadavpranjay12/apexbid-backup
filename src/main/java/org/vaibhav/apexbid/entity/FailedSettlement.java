package org.vaibhav.apexbid.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "failed_settlements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FailedSettlement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "auction_id")
    private Long auctionId;

    @Column(name = "request_id")
    private String requestId;

    // NEW: Tracks whether it failed during background sweep or user checkout
    @Column(name = "phase", nullable = false, length = 30)
    private String phase;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "failed_at", nullable = false, updatable = false)
    private Instant failedAt;

    @Column(name = "status", nullable = false)
    private String status;

    @PrePersist
    protected void onCreate() {
        this.failedAt = Instant.now();
        this.status = "PENDING_REVIEW";
    }
}