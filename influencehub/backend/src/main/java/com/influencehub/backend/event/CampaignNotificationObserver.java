package com.influencehub.backend.event;

import com.influencehub.backend.model.Notification;
import com.influencehub.backend.model.User;
import com.influencehub.backend.repository.NotificationRepository;
import com.influencehub.backend.repository.UserRepository;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Observer: fans a new campaign out to every influencer's notification feed.
 * Runs asynchronously, so POST /api/campaigns latency no longer grows with the number of
 * influencers on the platform (Tactic 5 — Introduce Concurrency).
 */
@Component
public class CampaignNotificationObserver {

    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;

    public CampaignNotificationObserver(UserRepository userRepository, NotificationRepository notificationRepository) {
        this.userRepository = userRepository;
        this.notificationRepository = notificationRepository;
    }

    @Async
    @EventListener
    @Transactional
    public void onCampaignCreated(CampaignCreatedEvent event) {
        LocalDateTime now = LocalDateTime.now();
        List<Notification> batch = userRepository.findAllByRoleIgnoreCase("influencer").stream()
                .map(influencer -> newNotification(influencer, event, now))
                .toList();
        notificationRepository.saveAll(batch);
    }

    private static Notification newNotification(User recipient, CampaignCreatedEvent event, LocalDateTime now) {
        Notification n = new Notification();
        n.setRecipient(recipient);
        n.setType("campaign");
        n.setText("New campaign posted: " + event.title());
        n.setLink("/influencer/campaigns/" + event.campaignId());
        n.setTimestamp(now);
        n.setRead(false);
        return n;
    }
}
