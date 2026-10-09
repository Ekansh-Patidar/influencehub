package com.influencehub.backend.influencer.repository;

import com.influencehub.backend.influencer.model.InfluencerProfile;
import com.influencehub.backend.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InfluencerProfileRepository extends JpaRepository<InfluencerProfile, Long> {

    Optional<InfluencerProfile> findByUserId(Long userId);

    Optional<InfluencerProfile> findByUser(User user);

    /** Discovery listing: filtering + paging done by the database, user fetched in the same query. */
    @Query(value = "SELECT p FROM InfluencerProfile p LEFT JOIN FETCH p.user",
           countQuery = "SELECT COUNT(p) FROM InfluencerProfile p")
    Page<InfluencerProfile> findPage(Pageable pageable);

    @Query(value = "SELECT p FROM InfluencerProfile p LEFT JOIN FETCH p.user WHERE p.niche IN :niches",
           countQuery = "SELECT COUNT(p) FROM InfluencerProfile p WHERE p.niche IN :niches")
    Page<InfluencerProfile> findPageByNicheIn(@Param("niches") Collection<String> niches, Pageable pageable);

    @Query("SELECT p FROM InfluencerProfile p LEFT JOIN FETCH p.user WHERE p.niche = :niche AND p.id <> :id")
    List<InfluencerProfile> findSimilar(@Param("niche") String niche, @Param("id") Long id, Pageable pageable);

    /** (userId, profileId) pairs for a set of users, in one query. */
    @Query("SELECT p.user.id, p.id FROM InfluencerProfile p WHERE p.user.id IN :userIds")
    List<Object[]> findProfileIdsByUserIds(@Param("userIds") Collection<Long> userIds);
}
