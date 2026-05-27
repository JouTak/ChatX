package cn.jason31416.chatx.channel.type.serverwide;

import cn.jason31416.chatx.channel.Channel;
import cn.jason31416.chatx.channel.type.ServerWideChannelHandler;
import cn.jason31416.chatx.message.Message;
import cn.jason31416.chatx.util.Config;
import cn.jason31416.chatx.util.SimplePlayer;
import net.kyori.adventure.text.Component;

import java.util.List;

public class LocalChannelHandler extends ServerWideChannelHandler {
    Channel channel;
    public LocalChannelHandler(Channel channel) {
        this.channel = channel;
    }

    @Override
    public List<SimplePlayer> getReceivers(SimplePlayer sender) {
        if (sender.getCurrentServer()==null) {
            return List.of();
        }
        return sender.getServerPlayers().stream().map(SimplePlayer::new).toList();
    }

    @Override
    public Component getPrefix(String text, SimplePlayer sender) {
        return new Message(text)
                .add("player", sender.getName())
                .add("channel", channel.getConfig(sender.getCurrentServer()).getDisplayName())
                .add("server", Config.getServerDisplayName(sender.getCurrentServer()))
                .toComponent(sender.getPlayer());
    }

    @Override
    public Channel getChannel() {
        return channel;
    }
}
