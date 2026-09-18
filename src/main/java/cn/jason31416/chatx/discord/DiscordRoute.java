package cn.jason31416.chatx.discord;

import javax.annotation.Nonnull;

public record DiscordRoute(
        @Nonnull Type type,
        @Nonnull String backend,
        @Nonnull String destinationId,
        @Nonnull String webhookUrl,
        @Nonnull String threadId
) {
    public String effectiveDestinationId() {
        return threadId.isBlank() ? destinationId : threadId;
    }

    public enum Type {
        GLOBAL,
        LOCAL
    }
}
