package net.allayfind.paper;

import java.time.Instant;
import java.util.UUID;

public record VoteEvent(UUID id, long serverId, String nickname, String edition, Instant createdAt) {}
