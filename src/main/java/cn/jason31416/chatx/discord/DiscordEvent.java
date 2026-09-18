package cn.jason31416.chatx.discord;

import cn.jason31416.chatx.channel.Channel;
import com.velocitypowered.api.proxy.Player;

import javax.annotation.Nonnull;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public record DiscordEvent(
        @Nonnull DiscordRoute.Type type,
        @Nonnull String backend,
        @Nonnull UUID playerUuid,
        @Nonnull String playerName
) {
    public static Optional<DiscordEvent> fromMinecraft(
            @Nonnull Channel channel,
            @Nonnull Player player,
            @Nonnull String backend
    ) {
        if(channel.getConfig(backend).getHandleMode() == Channel.HandleMode.PASSTHROUGH) return Optional.empty();

        DiscordRoute.Type type;
        try{
            type = DiscordRoute.Type.valueOf(channel.getType().toUpperCase(Locale.ROOT));
        }catch (IllegalArgumentException e){
            return Optional.empty();
        }
        return Optional.of(new DiscordEvent(type, backend, player.getUniqueId(), player.getUsername()));
    }
}
