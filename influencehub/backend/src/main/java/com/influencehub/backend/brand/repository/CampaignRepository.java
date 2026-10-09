package com.influencehub.backend.brand.repository;

import com.influencehub.backend.brand.model.Campaign;
import com.influencehub.backend.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {
    List<Campaign> findAllByBrand(User brand);
    List<Campaign> findAllByBrandAndStatus(User brand, String status);

    // Paged variants: the database does the LIMIT/OFFSET instead of loading every row.
    Page<Campaign> findAllByBrandId(Long brandId, Pageable pageable);
    Page<Campaign> findAllByBrand(User brand, Pageable pageable);
    Page<Campaign> findAllByBrandAndStatus(User brand, String status, Pageable pageable);
}
