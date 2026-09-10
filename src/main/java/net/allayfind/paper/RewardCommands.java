package net.allayfind.paper;

import java.util.UUID;
import java.util.function.Predicate;

final class RewardCommands {
    private RewardCommands() {}

    static String execute(Inbox.Reward reward, String onlineName, UUID playerId, Predicate<String> dispatch) {
        if (onlineName == null) return "pending";
        if (!onlineName.matches("[A-Za-z0-9_.]{1,32}")) return "uncertain";
        try {
            for (String command : reward.commands()) {
                String expanded = command.replace("{player}", onlineName)
                        .replace("{uuid}", playerId.toString()).replace("{vote_id}", reward.id());
                if (!dispatch.test(expanded)) return "uncertain";
            }
            return "done";
        } catch (RuntimeException error) { return "uncertain"; }
    }
}
