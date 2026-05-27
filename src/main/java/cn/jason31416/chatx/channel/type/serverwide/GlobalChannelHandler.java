package cn.jason31416.chatx.channel.type.serverwide;

import cn.jason31416.chatx.ChatX;
import cn.jason31416.chatx.channel.Channel;
import cn.jason31416.chatx.channel.type.ServerWideChannelHandler;
import cn.jason31416.chatx.message.Message;
import cn.jason31416.chatx.util.Config;
import cn.jason31416.chatx.util.SimplePlayer;
import lombok.SneakyThrows;
import net.kyori.adventure.text.Component;

import javax.annotation.Nonnull;
import java.util.List;

public class GlobalChannelHandler extends ServerWideChannelHandler {
    private final Channel channel;

    @SneakyThrows
    public GlobalChannelHandler(@Nonnull Channel channel) {
        this.channel = channel;
    }

    @Override
    public List<SimplePlayer> getReceivers(SimplePlayer sender) {
        return ChatX.getProxy().getAllPlayers().stream().map(SimplePlayer::new).toList();
    }

    @Override
    public Component getPrefix(String text, SimplePlayer sender) {
        Message msg = new Message(text);
        msg.add("player", sender.getName());
        msg.add("channel", channel.getConfig(sender.getCurrentServer()).getDisplayName());
        msg.add("server", Config.getServerDisplayName(sender.getCurrentServer()));
        return msg.toComponent(sender.getPlayer());
    }

    @Override
    public Channel getChannel() {
        return channel;
    }
}
