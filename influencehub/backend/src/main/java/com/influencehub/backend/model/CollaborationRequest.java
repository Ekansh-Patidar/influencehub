package com.influencehub.backend.model;

import com.influencehub.backend.brand.model.Campaign;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
public class CollaborationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "campaign_id")
    private Campaign campaign; // optional – brand may not link a specific campaign

    @ManyToOne
    @JoinColumn(name = "creator_id")
    private User creator;

    @ManyToOne
    @JoinColumn(name = "brand_id")
    private User brand;

    @Column(columnDefinition = "TEXT")
    private String message; // legacy field

    @Column(columnDefinition = "TEXT")
    private String description; // optional brand description when no campaign is selected

    private String status; // PENDING, ACCEPTED, REJECTED, COMPLETED

    private String initiatedBy; // INFLUENCER (applied to campaign) | BRAND (requested influencer)

    /**
     * Deduplication key, set only while the request is PENDING and cleared on accept/reject.
     * The UNIQUE constraint makes "at most one open request per brand/creator(/campaign)" a
     * database guarantee, so concurrent duplicate submissions cannot both be persisted.
     */
    @JsonIgnore
    @Column(unique = true)
    private String activeKey;

    private LocalDateTime timestamp;

    @PrePersist
    protected void onCreate() {
        timestamp = LocalDateTime.now();
        if (status == null) status = "PENDING";
    }
}
