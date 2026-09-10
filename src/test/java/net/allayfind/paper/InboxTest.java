package net.allayfind.paper;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InboxTest {
    @TempDir Path folder;
    VoteEvent vote = new VoteEvent(UUID.randomUUID(), 1, "Player_1", "java", Instant.now());

    @Test void duplicateDeliveryNeverCreatesSecondReward() throws Exception {
        try (Inbox inbox = new Inbox(folder.resolve("votes.db"), "site/1")) {
            inbox.save(List.of(vote), List.of("give {player} diamond 1"));
            inbox.save(List.of(vote), List.of("give {player} diamond 99"));
            var rewards = inbox.pending("player_1");
            assertEquals(1, rewards.size());
            assertEquals(List.of("give {player} diamond 1"), rewards.getFirst().commands());
            assertTrue(inbox.transition(vote.id().toString(), "pending", "executing"));
            assertFalse(inbox.transition(vote.id().toString(), "pending", "executing"));
            assertTrue(inbox.transition(vote.id().toString(), "executing", "done"));
            inbox.save(List.of(vote), List.of("give {player} diamond 99"));
            assertTrue(inbox.pending("Player_1").isEmpty());
        }
    }

    @Test void pendingSurvivesRestartAndInterruptedExecutionIsNotRetried() throws Exception {
        Path db = folder.resolve("votes.db");
        try (Inbox inbox = new Inbox(db, "site/1")) { inbox.save(List.of(vote), List.of("reward")); }
        try (Inbox inbox = new Inbox(db, "site/1")) {
            assertEquals(1, inbox.pending("Player_1").size());
            inbox.transition(vote.id().toString(), "pending", "executing");
        }
        try (Inbox inbox = new Inbox(db, "site/1")) {
            assertTrue(inbox.pending("Player_1").isEmpty());
            assertEquals("uncertain=1", inbox.status());
            assertTrue(inbox.transition(vote.id().toString(), "uncertain", "pending"));
            assertEquals(1, inbox.pending("Player_1").size());
        }
    }

    @Test void databaseCannotBeReusedForDifferentServer() throws Exception {
        Path db = folder.resolve("votes.db");
        try (Inbox inbox = new Inbox(db, "site/1")) { inbox.save(List.of(vote), List.of("reward")); }
        assertThrows(SQLException.class, () -> new Inbox(db, "site/2"));
    }

    @Test void differentPlayersAndUnknownIdsDoNotClaimEachOthersRewards() throws Exception {
        try (Inbox inbox = new Inbox(folder.resolve("votes.db"), "site/1")) {
            inbox.save(List.of(vote), List.of("reward"));
            assertTrue(inbox.pending("Other").isEmpty());
            assertFalse(inbox.transition(UUID.randomUUID().toString(), "pending", "done"));
        }
    }
}
