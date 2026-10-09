package com.influencehub.backend.brand.repository;

import com.influencehub.backend.brand.model.BrandProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BrandProfileRepository extends JpaRepository<BrandProfile, Long> {

    Optional<BrandProfile> findByUserId(Long userId);

    /** Batch lookup used to resolve brand display names for a whole page in one query. */
    List<BrandProfile> findAllByUserIdIn(Collection<Long> userIds);
}
