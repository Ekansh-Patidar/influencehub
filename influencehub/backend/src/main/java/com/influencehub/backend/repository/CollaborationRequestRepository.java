package com.influencehub.backend.repository;

import com.influencehub.backend.model.CollaborationRequest;
import com.influencehub.backend.model.User;
import com.influencehub.backend.brand.model.Campaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface CollaborationRequestRepository extends JpaRepository<CollaborationRequest, Long> {
    List<CollaborationRequest> findAllByBrand(User brand);
    List<CollaborationRequest> findAllByCreator(User creator);
    List<CollaborationRequest> findAllByCampaign(Campaign campaign);
    long countByCampaign(Campaign campaign);

    /**
     * Check if an ACCEPTED request exists between two users in either direction.
     * Used by MessageController to enforce: messaging allowed only after acceptance.
     */
    @Query("SELECT COUNT(r) > 0 FROM CollaborationRequest r WHERE r.status = 'ACCEPTED' AND " +
           "((r.brand = :u1 AND r.creator = :u2) OR (r.brand = :u2 AND r.creator = :u1))")
    boolean existsAcceptedBetween(@Param("u1") User u1, @Param("u2") User u2);

    /**
     * Atomic compare-and-set of the status: succeeds (returns 1) only if the row is still in
     * the expected state. Two racing ACCEPT/REJECT calls can therefore never both "win".
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CollaborationRequest r SET r.status = :to, r.activeKey = NULL " +
           "WHERE r.id = :id AND r.status = :from")
    int transitionStatus(@Param("id") Long id, @Param("from") String from, @Param("to") String to);

    // ── Set-based queries replacing the old load-everything-then-filter-in-Java (N+1) code ──

    /** Inbox / outbox listing with campaign + counterpart fetched in the same SELECT. */
    @Query("SELECT r FROM CollaborationRequest r LEFT JOIN FETCH r.campaign JOIN FETCH r.creator " +
           "WHERE r.brand = :brand AND r.initiatedBy = :initiatedBy ORDER BY r.timestamp DESC")
    List<CollaborationRequest> findForBrand(@Param("brand") User brand, @Param("initiatedBy") String initiatedBy);

    @Query("SELECT r FROM CollaborationRequest r LEFT JOIN FETCH r.campaign JOIN FETCH r.brand " +
           "WHERE r.creator = :creator AND r.initiatedBy = :initiatedBy ORDER BY r.timestamp DESC")
    List<CollaborationRequest> findForCreator(@Param("creator") User creator, @Param("initiatedBy") String initiatedBy);

    @Query("SELECT r FROM CollaborationRequest r LEFT JOIN FETCH r.campaign " +
           "WHERE r.creator = :creator AND (r.initiatedBy = 'INFLUENCER' OR (r.initiatedBy IS NULL AND r.campaign IS NOT NULL)) " +
           "ORDER BY r.timestamp DESC")
    List<CollaborationRequest> findApplicationsByCreator(@Param("creator") User creator);

    /** Lightweight projection: (creatorUserId, status) for every request a brand is part of. */
    @Query("SELECT r.creator.id, r.status FROM CollaborationRequest r WHERE r.brand = :brand")
    List<Object[]> findCreatorStatusPairsForBrand(@Param("brand") User brand);

    @Query("SELECT r.status FROM CollaborationRequest r WHERE r.brand = :brand AND r.creator.id = :creatorId " +
           "AND r.initiatedBy = 'BRAND' ORDER BY r.timestamp DESC")
    List<String> findBrandInitiatedStatuses(@Param("brand") User brand, @Param("creatorId") Long creatorId);

    @Query("SELECT r.status FROM CollaborationRequest r WHERE r.creator = :creator AND r.campaign.id = :campaignId " +
           "AND (r.initiatedBy = 'INFLUENCER' OR r.initiatedBy IS NULL) ORDER BY r.timestamp DESC")
    List<String> findApplicationStatuses(@Param("creator") User creator, @Param("campaignId") Long campaignId);

    @Query("SELECT COUNT(r) > 0 FROM CollaborationRequest r WHERE r.status = 'PENDING' AND r.creator = :creator " +
           "AND r.campaign.id = :campaignId")
    boolean existsPendingApplication(@Param("creator") User creator, @Param("campaignId") Long campaignId);

    @Query("SELECT COUNT(r) > 0 FROM CollaborationRequest r WHERE r.status = 'PENDING' AND r.brand = :brand " +
           "AND r.creator.id = :creatorId")
    boolean existsPendingBetween(@Param("brand") User brand, @Param("creatorId") Long creatorId);

    /** Request counts for a whole page of campaigns in one GROUP BY query. */
    @Query("SELECT r.campaign.id, COUNT(r) FROM CollaborationRequest r WHERE r.campaign.id IN :ids GROUP BY r.campaign.id")
    List<Object[]> countByCampaignIds(@Param("ids") Collection<Long> ids);

    // ── Account deletion (bulk, inside one transaction) ──
    @Modifying
    @Query("DELETE FROM CollaborationRequest r WHERE r.brand = :u OR r.creator = :u " +
           "OR r.campaign.id IN (SELECT c.id FROM Campaign c WHERE c.brand = :u)")
    int deleteAllInvolving(@Param("u") User u);
}
