package com.influencehub.backend.event;

/**
 * Observer pattern (Report §3.2, Pattern 1): published by the campaign subject when a
 * campaign is created; observers react without the campaign code knowing about them.
 */
public record CampaignCreatedEvent(Long campaignId, String title) {
}
