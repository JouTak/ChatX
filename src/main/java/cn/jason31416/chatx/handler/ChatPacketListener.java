package cn.jason31416.chatx.handler;

import cn.jason31416.chatx.ChatX;
import cn.jason31416.chatx.channel.Channel;
import cn.jason31416.chatx.discord.DiscordEvent;
import cn.jason31416.chatx.discord.DiscordManager;
import cn.jason31416.chatx.util.Config;
import cn.jason31416.chatx.util.Logger;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.SimplePacketListenerAbstract;
import com.github.retrooper.packetevents.event.simple.PacketPlaySendEvent;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.chat.ChatType;
import com.github.retrooper.packetevents.protocol.chat.StaticChatType;
import com.github.retrooper.packetevents.protocol.chat.message.*;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChatMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisguisedChat;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import javax.annotation.Nonnull;

public class ChatPacketListener extends SimplePacketListenerAbstract {
    public ChatPacketListener() {
        super(PacketListenerPriority.NORMAL);
    }
    public void attemptRewriteSignedPacket(@Nonnull PacketPlaySendEvent event) {
        if(event.getPacketType() != PacketType.Play.Server.CHAT_MESSAGE){
            return;
        }

        WrapperPlayServerChatMessage signedPacket = new WrapperPlayServerChatMessage(event);
        Component chatContent = signedPacket.getMessage().getChatContent();

        Player pl = event.getPlayer();
        if(signedPacket.getMessage() instanceof ChatMessage_v1_19_1 messageV1191) {
            event.setCancelled(true);
            WrapperPlayServerDisguisedChat unsignedPacket = new WrapperPlayServerDisguisedChat(
                    chatContent,
                    messageV1191.getChatFormatting());
            PacketEvents.getAPI().getPlayerManager().sendPacket(pl, unsignedPacket);
        }else if(signedPacket.getMessage() instanceof ChatMessage_v1_19_3 messageV1193) {
            event.setCancelled(true);
            WrapperPlayServerDisguisedChat unsignedPacket = new WrapperPlayServerDisguisedChat(
                    chatContent,
                    messageV1193.getChatFormatting());
            PacketEvents.getAPI().getPlayerManager().sendPacket(pl, unsignedPacket);
        }
        return;
    }
    @Override
    public void onPacketPlaySend(@Nonnull PacketPlaySendEvent event) {
        try{
            if(event.isCancelled()) return;
            if(event.getPlayer()==null || ((Player) event.getPlayer()).getCurrentServer().isEmpty() || event.getConnectionState() != ConnectionState.PLAY){
                return;
            }

            // Extract necessary information
            Component message;
            Player player;
            boolean isSigned = false;
            if(event.getPacketType() == PacketType.Play.Server.DISGUISED_CHAT){
                WrapperPlayServerDisguisedChat packet = new WrapperPlayServerDisguisedChat(event);
                String playerName = PlainTextComponentSerializer.plainText().serialize(packet.getChatFormatting().getName());
                if(packet.getChatFormatting().getTargetName()!=null){
                    Logger.debug("asserting /msg is used");
                    return;
                }
                var optPlayer = ChatX.getProxy().getPlayer(playerName);
                if(optPlayer.isEmpty()){
                    Logger.debug("player not found");
                    return;
                }
                player = optPlayer.get();
                message = packet.getMessage();
            }else if(event.getPacketType() == PacketType.Play.Server.CHAT_MESSAGE){
                WrapperPlayServerChatMessage packet = new WrapperPlayServerChatMessage(event);
                if(!(packet.getMessage() instanceof ChatMessage_v1_16 messageV116)){
                    Logger.debug("player version lower than 1.16; Couldn't handle packet.");
                    attemptRewriteSignedPacket(event);
                    return;
                }
                if(packet.getMessage() instanceof ChatMessage_v1_19_1 messageV1191){
                    if(messageV1191.getChatFormatting().getTargetName()!=null) {
                        Logger.debug("v1.19.1 target name is "+messageV1191.getChatFormatting().getTargetName()+", not null, therefore assume is /msg");
                        attemptRewriteSignedPacket(event);
                        return;
                    }
                }else if(packet.getMessage() instanceof ChatMessage_v1_19_3 messageV1193){
                    if(messageV1193.getChatFormatting().getTargetName()!=null) {
                        attemptRewriteSignedPacket(event);
                        Logger.debug("v1.19.3 target name is "+messageV1193.getChatFormatting().getTargetName()+", not null, therefore assume is /msg");
                        return;
                    }
                }
                var optPlayer = ChatX.getProxy().getPlayer(messageV116.getSenderUUID());
                if(optPlayer.isEmpty()){
                    attemptRewriteSignedPacket(event);
                    Logger.debug("player not found");
                    return;
                }
                player = optPlayer.get();
                message = messageV116.getChatContent();
                isSigned = true;
            }else{
                // Not the packet type that we are looking for
                return;
            }

            // Process chat

            if(!player.equals(event.getPlayer())){
                // Only process one packet of a message.
                if(isSigned)
                    attemptRewriteSignedPacket(event);
                event.setCancelled(true);
                return;
            }

            DiscordManager discordManager = ChatX.getDiscordManager();
            if(discordManager != null){
                DiscordEvent discordEvent = discordManager.pollMinecraft(player.getUniqueId());
                if(discordEvent != null) discordManager.publishMinecraft(discordEvent, message);
            }

            Channel channel = Channel.getPlayerChannel(player);

            if(channel.getConfig(player).getHandleMode()== Channel.HandleMode.PASSTHROUGH) {
                Logger.debug("channel handle mode is PASSTHROUGH");
                if(isSigned)
                    attemptRewriteSignedPacket(event);
                return;
            }else if(channel.getConfig(player).getHandleMode() == Channel.HandleMode.IGNORE_BACKEND){
                event.setCancelled(true);
                return;
            }else if(channel.getConfig(player).getHandleMode() == Channel.HandleMode.RESPECT_BACKEND
                    || channel.getConfig(player).getHandleMode() == Channel.HandleMode.NOTIFY_BACKEND){
                event.setCancelled(true);

                ChatX.getProxy().getScheduler().buildTask(ChatX.getInstance(), ()->{
                    Channel.handleProcessedChat(
                            player,
                            channel,
                            message
                    );
                }).schedule();
                return;
            }

        }catch (Exception e){
            if(Config.getBoolean("debug")) {
                Logger.error("Error occured when processing chat packet: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

}
