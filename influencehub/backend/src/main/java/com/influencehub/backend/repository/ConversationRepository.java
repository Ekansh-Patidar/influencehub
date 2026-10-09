package com.influencehub.backend.repository;

import com.influencehub.backend.model.Conversation;
import com.influencehub.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    @Query("SELECT c FROM Conversation c WHERE (c.user1 = :user OR c.user2 = :user)")
    List<Conversation> findAllByParticipant(@Param("user") User user);

    @Query("SELECT c FROM Conversation c WHERE (c.user1 = :u1 AND c.user2 = :u2) OR (c.user1 = :u2 AND c.user2 = :u1)")
    Optional<Conversation> findBetweenUsers(@Param("u1") User u1, @Param("u2") User u2);

    @Modifying
    @Query("DELETE FROM Conversation c WHERE c.user1 = :u OR c.user2 = :u")
    int deleteAllInvolving(@Param("u") User u);
}
