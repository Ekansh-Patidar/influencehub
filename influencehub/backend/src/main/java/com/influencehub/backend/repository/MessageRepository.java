package com.influencehub.backend.repository;

import com.influencehub.backend.model.Conversation;
import com.influencehub.backend.model.Message;
import com.influencehub.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findAllByConversationOrderByTimestampAsc(Conversation conversation);

    /** Every message in any conversation the user takes part in (covers messages they sent). */
    @Modifying
    @Query("DELETE FROM Message m WHERE m.sender = :u OR m.conversation.id IN " +
           "(SELECT c.id FROM Conversation c WHERE c.user1 = :u OR c.user2 = :u)")
    int deleteAllInvolving(@Param("u") User u);
}
