package cn.jason31416.chatx.handler;

import lombok.Getter;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import javax.annotation.Nonnull;

@Getter
public class DiscordManager {
    private final JDA jda;

    public DiscordManager(@Nonnull JDA jda) {
        this.jda = jda;
    }

    public boolean publish(@Nonnull String destinationId, @Nonnull MessageCreateData message) {
        MessageChannel channel = jda.getChannelById(MessageChannel.class, destinationId);
        if(channel == null) return false;
        channel.sendMessage(message).queue();
        return true;
    }
}
