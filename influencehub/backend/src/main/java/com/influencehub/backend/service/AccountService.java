package com.influencehub.backend.service;

import com.influencehub.backend.brand.repository.BrandProfileRepository;
import com.influencehub.backend.brand.repository.CampaignRepository;
import com.influencehub.backend.influencer.repository.InfluencerProfileRepository;
import com.influencehub.backend.model.User;
import com.influencehub.backend.repository.CollaborationRequestRepository;
import com.influencehub.backend.repository.ConversationRepository;
import com.influencehub.backend.repository.MessageRepository;
import com.influencehub.backend.repository.NotificationRepository;
import com.influencehub.backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account deletion as ONE ACID transaction (Report §4.2.1.4): a user and everything that
 * references them is removed together, or — on any failure — nothing is removed.
 * Deletion order follows the foreign-key graph (children before parents).
 */
@Service
public class AccountService {

    private final UserRepository userRepository;
    private final BrandProfileRepository brandProfileRepository;
    private final InfluencerProfileRepository influencerProfileRepository;
    private final CampaignRepository campaignRepository;
    private final CollaborationRequestRepository requestRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final NotificationRepository notificationRepository;

    public AccountService(UserRepository userRepository, BrandProfileRepository brandProfileRepository,
                          InfluencerProfileRepository influencerProfileRepository,
                          CampaignRepository campaignRepository, CollaborationRequestRepository requestRepository,
                          ConversationRepository conversationRepository, MessageRepository messageRepository,
                          NotificationRepository notificationRepository) {
        this.userRepository = userRepository;
        this.brandProfileRepository = brandProfileRepository;
        this.influencerProfileRepository = influencerProfileRepository;
        this.campaignRepository = campaignRepository;
        this.requestRepository = requestRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.notificationRepository = notificationRepository;
    }

    @Transactional
    public void deleteAccount(User user) {
        notificationRepository.deleteAllForRecipient(user);
        messageRepository.deleteAllInvolving(user);
        conversationRepository.deleteAllInvolving(user);
        requestRepository.deleteAllInvolving(user);
        // Entity deletes (not bulk JPQL) so the campaigns' element-collection rows cascade too
        campaignRepository.deleteAll(campaignRepository.findAllByBrand(user));
        brandProfileRepository.findByUserId(user.getId()).ifPresent(brandProfileRepository::delete);
        influencerProfileRepository.findByUserId(user.getId()).ifPresent(influencerProfileRepository::delete);
        userRepository.delete(user);
    }
}
