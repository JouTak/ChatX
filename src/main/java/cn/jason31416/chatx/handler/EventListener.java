package cn.jason31416.chatx.handler;

import cn.jason31416.chatx.util.*;
import cn.jason31416.chatx.discord.DiscordEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import cn.jason31416.chatx.ChatX;
import cn.jason31416.chatx.channel.Channel;
import cn.jason31416.chatx.command.DirectMessageCommand;
import cn.jason31416.chatx.discord.DiscordManager;
import cn.jason31416.chatx.message.Message;
import cn.jason31416.chatx.module.PatternModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import javax.annotation.Nonnull;
import java.util.List;

public class EventListener {
    @SuppressWarnings("deprecation")
    @Subscribe(priority = Short.MIN_VALUE)
    public void onPlayerChat(@Nonnull PlayerChatEvent event) {
        if(!event.getResult().isAllowed()) return;
        long timemuted = PunishmentHandler.fetchMuted(new SimplePlayer(event.getPlayer()));
        if(timemuted!=-1){
            event.getPlayer().sendMessage(Message.getMessage("chat.player-is-muted").add("time_left", TimeUtil.displayMillis(timemuted-System.currentTimeMillis())).toComponent());
            cancelChat(event);
            return;
        }

        Channel channel;
        String message;
        boolean prefixedChannel = !event.getMessage().isEmpty()
                && Channel.channelPrefixes.containsKey(event.getMessage().substring(0, 1));
        if (prefixedChannel) {
            channel = Channel.channelPrefixes.get(event.getMessage().substring(0, 1));
            message = event.getMessage().substring(1);
        } else {
            channel = Channel.getPlayerChannel(event.getPlayer());
            message = event.getMessage();
        }

        if (channel == null) return;
        if (event.getPlayer().getCurrentServer().isEmpty()) return;

        String serverid = event.getPlayer().getCurrentServer().get().getServerInfo().getName();

        if (channel.getRestrictedServers().contains(serverid)) {
            cancelChat(event);
            event.getPlayer().sendMessage(Message.getMessage("chat.channel-declined").toComponent());
            return;
        }

        if(channel.getConfig(serverid).getSendPermission()!=null&&!event.getPlayer().hasPermission(channel.getConfig(serverid).getSendPermission())){
            event.getPlayer().sendMessage(Message.getMessage("chat.no-send-permission").toComponent());
            cancelChat(event);
            return;
        }

        if(channel.getConfig(event.getPlayer()).getRateLimiter()!=null&&!channel.getConfig(event.getPlayer()).getRateLimiter().invoke(event.getPlayer().getUsername())){
            event.getPlayer().sendMessage(Message.getMessage("chat.rate-limited").toComponent());
            cancelChat(event);
            return;
        }

        if (prefixedChannel) {
            Channel selectedChannel = channel;
            DiscordEvent discordEvent = DiscordEvent.fromMinecraft(
                    selectedChannel,
                    event.getPlayer(),
                    serverid
            ).orElse(null);
            ChatX.getProxy().getScheduler().buildTask(ChatX.getInstance(), () ->
                    Channel.handleChat(event.getPlayer(), selectedChannel, message, discordEvent)
            ).schedule();
            cancelChat(event);
            return;
        }

        if(channel.getConfig(serverid).getHandleMode() == Channel.HandleMode.PASSTHROUGH){
            // Allow the packet to passthrough
            ChatX.getProxy().getScheduler().buildTask(ChatX.getInstance(), ()->{
                channel.getHandler().logToConsole(new SimplePlayer(event.getPlayer()), message);
            }).schedule();
            return;
        }else if(channel.getConfig(serverid).getHandleMode() == Channel.HandleMode.NOTIFY_BACKEND){
            // Let the backend produce the final component. ChatPacketListener will redistribute it.
            queueDiscordEvent(channel, event.getPlayer(), serverid);
            return;
        }else if(channel.getConfig(serverid).getHandleMode() == Channel.HandleMode.RESPECT_BACKEND){
            // Allow the packet to passthrough
            queueDiscordEvent(channel, event.getPlayer(), serverid);
            return;
        }else if(channel.getConfig(serverid).getHandleMode() == Channel.HandleMode.IGNORE_BACKEND){
            DiscordEvent discordEvent = DiscordEvent.fromMinecraft(
                    channel,
                    event.getPlayer(),
                    serverid
            ).orElse(null);
            ChatX.getProxy().getScheduler().buildTask(ChatX.getInstance(), ()->{
                Channel.handleChat(event.getPlayer(), channel, message, discordEvent);
            }).schedule();
            cancelChat(event);
            return;
        }else{
            throw new ShitMountainException("Theres only fking four types of handlemodes.");
        }

    }

    private void cancelChat(@Nonnull PlayerChatEvent event) {
        if(event.getPlayer().getProtocolVersion().compareTo(ProtocolVersion.MINECRAFT_1_19_1) >= 0){
            ChatPacketListener.suppressBackendEcho(event.getPlayer(), event.getMessage());
            return;
        }
        event.setResult(PlayerChatEvent.ChatResult.denied());
    }

    private void queueDiscordEvent(
            @Nonnull Channel channel,
            @Nonnull Player player,
            @Nonnull String backend
    ) {
        DiscordManager discordManager = ChatX.getDiscordManager();
        if(discordManager == null) return;
        DiscordEvent.fromMinecraft(channel, player, backend)
                .ifPresent(discordManager::queueMinecraft);
    }

    @Subscribe
    public void onPlayerJoin(@Nonnull LoginEvent event){
        Channel.getPlayerChannels().put(event.getPlayer().getUniqueId(), Channel.defaultChannel);
    }

    @Subscribe
    public void onPlayerLeave(@Nonnull DisconnectEvent event){
        ChatPacketListener.discardBackendEcho(event.getPlayer().getUniqueId());
        if(ChatX.getDiscordManager() != null){
            ChatX.getDiscordManager().discardMinecraft(event.getPlayer().getUniqueId());
        }
        Channel.getPlayerChannels().remove(event.getPlayer().getUniqueId());
        PlayerData.getPlayerDataMap().remove(event.getPlayer().getUniqueId());
        DirectMessageCommand.lastMessage.remove(event.getPlayer().getUniqueId());
    }
}
