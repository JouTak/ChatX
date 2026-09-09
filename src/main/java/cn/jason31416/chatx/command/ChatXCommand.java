package cn.jason31416.chatx.command;

import cn.jason31416.chatx.handler.PunishmentHandler;
import cn.jason31416.chatx.util.Config;
import cn.jason31416.chatx.util.SimplePlayer;
import cn.jason31416.chatx.util.TimeUtil;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import cn.jason31416.chatx.ChatX;
import cn.jason31416.chatx.gui.GUIContainer;
import cn.jason31416.chatx.gui.GUIData;
import cn.jason31416.chatx.message.Message;
import cn.jason31416.chatx.module.ShowItemModule;

import java.util.List;
import java.util.UUID;

public class ChatXCommand implements SimpleCommand {
    @Override
    public void execute(Invocation invocation) {
        if(invocation.arguments().length == 0 || invocation.arguments()[0].equals("version")){
            String version = ChatX.getProxy().getPluginManager().getPlugin("chatx")
                    .flatMap(container -> container.getDescription().getVersion())
                    .orElse("unknown");
            String name = Config.getConfigTree().getString("branding.name", "ChatXJT");
            List<String> authors = Config.getConfigTree().getStringList("branding.authors");
            if (authors.isEmpty()) {
                authors = List.of("EnderDissa");
            }
            invocation.source().sendMessage(new Message(
                    "<#47BFFB>" + name + " v" + version + " by " + String.join(", ", authors)
            ).toComponent());
            return;
        }
        switch (invocation.arguments()[0]){
            case "reload" -> {
                if(invocation.source().hasPermission("chatx.admin")){
                    ChatX.reload();
                    invocation.source().sendMessage(new Message("<#47BFFB>ChatX has been reloaded.").toComponent());
                }
            }
            case "mute" -> {
                if(invocation.source().hasPermission("chatx.admin")||invocation.source().hasPermission("chatx.manage.mute")){
                    String sender;
                    if(invocation.source() instanceof Player pl){
                        sender = pl.getUsername();
                    }else{
                        sender = "CONSOLE";
                    }
                    if(invocation.arguments().length<=1){
                        invocation.source().sendMessage(Message.getMessage("command.mute-command-usage").toComponent());
                        return;
                    }
                    String targetString = invocation.arguments()[1];
                    if(ChatX.getProxy().getPlayer(targetString).isEmpty()){
                        invocation.source().sendMessage(Message.getMessage("command.mute-command-usage").toComponent());
                        return;
                    }
                    Player target = ChatX.getProxy().getPlayer(targetString).get();
                    String timeString = invocation.arguments().length>2?invocation.arguments()[2]:"1000w";
                    String reason = invocation.arguments().length>3?String.join(" ", List.of(invocation.arguments()).subList(3, invocation.arguments().length)):"Muted by admin";
                    long time;
                    try{
                        time = TimeUtil.convertToMillis(timeString);
                    }catch(NumberFormatException e){
                        invocation.source().sendMessage(Message.getMessage("command.mute-command-time").toComponent());
                        return;
                    }
                    PunishmentHandler.mutePlayer(new SimplePlayer(target), sender, reason, time);
                    invocation.source().sendMessage(Message.getMessage("command.mute-command-success")
                                    .add("player", target.getUsername())
                                    .add("time", timeString)
                            .toComponent());
                }
            }
            case "unmute" -> {
                if(invocation.source().hasPermission("chatx.admin")||invocation.source().hasPermission("chatx.manage.unmute")){
                    if(invocation.arguments().length<=1){
                        invocation.source().sendMessage(Message.getMessage("command.unmute-command-usage").toComponent());
                        return;
                    }
                    String targetString = invocation.arguments()[1];
                    if(ChatX.getProxy().getPlayer(targetString).isEmpty()){
                        invocation.source().sendMessage(Message.getMessage("command.unmute-command-usage").toComponent());
                        return;
                    }
                    SimplePlayer target = new SimplePlayer(ChatX.getProxy().getPlayer(targetString).get());
                    if(PunishmentHandler.fetchMuted(target)==-1){
                        invocation.source().sendMessage(Message.getMessage("command.target-not-muted").toComponent());
                        return;
                    }
                    PunishmentHandler.unmutePlayer(target);
                    invocation.source().sendMessage(Message.getMessage("command.unmute-command-success").add("player", target.getName()).toComponent());
                }
            }
            case "item" -> {
                if(invocation.arguments().length == 2) {
                    UUID uuid = UUID.fromString(invocation.arguments()[1]);
                    if(ShowItemModule.getGuiMap().containsKey(uuid) && invocation.source() instanceof Player player) {
                        GUIData data = ShowItemModule.getGuiMap().get(uuid);
                        GUIContainer container = GUIContainer.of(data);
                        container.open(player);
                    }
                }
            }
            default -> {
                invocation.source().sendMessage(new Message("&cUnknown chatx command.").toComponent());
            }
        }
    }
    @Override
    public List<String> suggest(Invocation invocation) {
        if(invocation.arguments().length <= 1){
            return List.of("version", "reload", "mute", "unmute");
        }
        if(invocation.arguments().length == 2){
            if(invocation.arguments()[0].equals("mute")||invocation.arguments()[0].equals("unmute")){
                return ChatX.getProxy().getAllPlayers().stream().map(Player::getUsername).toList();
            }
        }
        if(invocation.arguments().length == 3){
            if(invocation.arguments()[0].equals("mute")){
                return List.of("[time(w|d|h|m|s)]");
            }
        }
        if(invocation.arguments().length >= 4){
            if(invocation.arguments()[0].equals("mute")){
                return List.of("[reason]");
            }
        }
        return List.of();
    }
}
