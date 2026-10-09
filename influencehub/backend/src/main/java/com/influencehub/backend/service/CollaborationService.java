package com.influencehub.backend.service;

import com.influencehub.backend.brand.model.Campaign;
import com.influencehub.backend.brand.repository.CampaignRepository;
import com.influencehub.backend.exception.ApiException;
import com.influencehub.backend.model.CollaborationRequest;
import com.influencehub.backend.model.User;
import com.influencehub.backend.repository.CollaborationRequestRepository;
import com.influencehub.backend.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;

/**
 * Collaboration workflow business rules (Report §1.2.4):
 *  - at most one open (PENDING) request per brand/creator (or creator/campaign for applications),
 *    guaranteed by a UNIQUE key so concurrent duplicates are impossible;
 *  - status is a state machine: PENDING -> ACCEPTED | REJECTED, ACCEPTED -> COMPLETED;
 *  - only the RECIPIENT of a request may accept/reject it (the initiator cannot self-accept
 *    and thereby unlock messaging);
 *  - transitions are atomic compare-and-set updates (no lost updates under concurrency).
 */
@Service
public class CollaborationService {

    private static final Set<String> KNOWN_STATUSES = Set.of("PENDING", "ACCEPTED", "REJECTED", "COMPLETED");

    /** Allowed transitions: target status -> required current status. */
    private static final Map<String, String> REQUIRED_FROM = Map.of(
            "ACCEPTED", "PENDING",
            "REJECTED", "PENDING",
            "COMPLETED", "ACCEPTED");

    private final CollaborationRequestRepository requestRepository;
    private final CampaignRepository campaignRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    public CollaborationService(CollaborationRequestRepository requestRepository,
                                CampaignRepository campaignRepository,
                                UserRepository userRepository,
                                NotificationService notificationService) {
        this.requestRepository = requestRepository;
        this.campaignRepository = campaignRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    /** Influencer applies to a campaign. */
    @Transactional
    public CollaborationRequest apply(User influencer, Long campaignId, String message) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Invalid campaignId"));
        if (requestRepository.existsPendingApplication(influencer, campaignId)) {
            throw new ApiException(HttpStatus.CONFLICT, "You have already applied for this campaign.");
        }
        CollaborationRequest request = new CollaborationRequest();
        request.setBrand(campaign.getBrand());
        request.setCreator(influencer);
        request.setCampaign(campaign);
        request.setInitiatedBy("INFLUENCER");
        request.setDescription(message);
        request.setMessage(message);
        request.setActiveKey("APP:" + influencer.getId() + ":" + campaignId);
        CollaborationRequest saved = requestRepository.saveAndFlush(request); // UNIQUE(activeKey) enforced here

        notificationService.notify(campaign.getBrand(), "request",
                influencer.getName() + " applied to your campaign: " + campaign.getTitle(), "/brand/requests");
        return saved;
    }

    /** Brand invites a creator (optionally for a specific campaign). */
    @Transactional
    public CollaborationRequest invite(User brand, Long creatorId, Long campaignId,
                                       String description, String message, String brandDisplayName) {
        User creator = userRepository.findById(creatorId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Invalid creatorId"));
        if (!"influencer".equalsIgnoreCase(creator.getRole())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Collaboration requests can only be sent to creators.");
        }
        if (requestRepository.existsPendingBetween(brand, creatorId)) {
            throw new ApiException(HttpStatus.CONFLICT, "A pending request to this creator already exists.");
        }
        CollaborationRequest request = new CollaborationRequest();
        request.setBrand(brand);
        request.setCreator(creator);
        request.setDescription(description != null ? description : message);
        request.setMessage(message);
        request.setInitiatedBy("BRAND");
        if (campaignId != null) {
            campaignRepository.findById(campaignId)
                    .filter(c -> c.getBrand() != null && c.getBrand().getId().equals(brand.getId()))
                    .ifPresent(request::setCampaign);
        }
        request.setActiveKey("INV:" + brand.getId() + ":" + creatorId);
        CollaborationRequest saved = requestRepository.saveAndFlush(request); // UNIQUE(activeKey) enforced here

        String campaignName = saved.getCampaign() != null ? saved.getCampaign().getTitle() : "a new opportunity";
        notificationService.notify(creator, "request",
                brandDisplayName + " sent you a collaboration request for " + campaignName, "/influencer/requests");
        return saved;
    }

    @Transactional
    public CollaborationRequest updateStatus(Long requestId, String rawStatus, User actor) {
        if (rawStatus == null || !KNOWN_STATUSES.contains(rawStatus.toUpperCase())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown status: " + rawStatus);
        }
        String target = rawStatus.toUpperCase();
        CollaborationRequest req = requestRepository.findById(requestId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Request not found"));

        boolean isBrand = req.getBrand().getId().equals(actor.getId());
        boolean isCreator = req.getCreator().getId().equals(actor.getId());
        if (!isBrand && !isCreator) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden: you are not a participant in this request");
        }

        String from = REQUIRED_FROM.get(target);
        if (from == null) {
            throw new ApiException(HttpStatus.CONFLICT, "Cannot move a request back to " + target);
        }
        if ("ACCEPTED".equals(target) || "REJECTED".equals(target)) {
            boolean actorIsInitiator = brandInitiated(req) ? isBrand : isCreator;
            if (actorIsInitiator) {
                throw new ApiException(HttpStatus.FORBIDDEN,
                        "Only the recipient of a collaboration request can accept or reject it");
            }
        }

        // Atomic compare-and-set: only one concurrent caller can move the row out of `from`.
        if (requestRepository.transitionStatus(requestId, from, target) == 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Request is no longer " + from + " (it may have been updated by someone else)");
        }
        req.setStatus(target);

        // Observer-pattern notification: notify the OTHER party
        String statusLabel = target.toLowerCase();
        if (isBrand) {
            notificationService.notify(req.getCreator(), "request",
                    req.getBrand().getName() + " has " + statusLabel + " your collaboration request",
                    "/influencer/requests");
        } else {
            notificationService.notify(req.getBrand(), "request",
                    req.getCreator().getName() + " has " + statusLabel + " your collaboration request",
                    "/brand/requests");
        }
        return req;
    }

    /** Legacy rows have no initiatedBy: campaign applications were the influencer-initiated kind. */
    private static boolean brandInitiated(CollaborationRequest r) {
        if (r.getInitiatedBy() != null) return "BRAND".equals(r.getInitiatedBy());
        return r.getCampaign() == null;
    }
}
