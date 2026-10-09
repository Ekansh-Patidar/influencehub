package com.influencehub.backend.repository;

import com.influencehub.backend.model.Notification;
import com.influencehub.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findAllByRecipientOrderByTimestampDesc(User recipient);
    List<Notification> findAllByRecipientAndTypeOrderByTimestampDesc(User recipient, String type);
    long countByRecipientAndIsReadFalse(User recipient);

    @Modifying
    @Query("DELETE FROM Notification n WHERE n.recipient = :u")
    int deleteAllForRecipient(@Param("u") User u);
}
