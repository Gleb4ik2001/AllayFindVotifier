package net.allayfind.paper;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RewardCommandsTest {
    UUID player = UUID.randomUUID();
    Inbox.Reward reward = new Inbox.Reward(UUID.randomUUID().toString(), "player", List.of("give {player} diamond 1", "log {uuid} {vote_id}"));

    @Test void offlinePlayerStaysPending() {
        assertEquals("pending", RewardCommands.execute(reward, null, player, command -> { fail(); return true; }));
    }
    @Test void successUsesActualPlayerAndEventIdentifiers() {
        List<String> commands = new ArrayList<>();
        assertEquals("done", RewardCommands.execute(reward, "Player", player, command -> commands.add(command)));
        assertEquals(List.of("give Player diamond 1", "log " + player + " " + reward.id()), commands);
    }
    @Test void partialFailureRequiresManualCheck() {
        List<String> commands = new ArrayList<>();
        assertEquals("uncertain", RewardCommands.execute(reward, "Player", player, command -> {
            commands.add(command); return commands.size() == 1;
        }));
        assertEquals(2, commands.size());
        assertEquals("uncertain", RewardCommands.execute(reward, "Player", player, command -> { throw new IllegalStateException(); }));
    }
    @Test void invalidPlayerCannotInjectCommands() {
        assertEquals("uncertain", RewardCommands.execute(reward, "Player;op", player, command -> { fail(); return true; }));
    }
}
