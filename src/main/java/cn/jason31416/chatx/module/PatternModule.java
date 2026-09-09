package cn.jason31416.chatx.module;

import com.velocitypowered.api.proxy.Player;
import cn.jason31416.chatx.channel.Channel;
import cn.jason31416.chatx.util.SimplePlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public abstract class PatternModule {
    private static final Map<String, PatternModule> modules = new ConcurrentHashMap<>();

    public abstract Component handle(Player sender, List<SimplePlayer> receivers);

    public static void registerModule(String id, PatternModule module) {
        modules.put(id, module);
    }

    public static void registerDefaults() {
//        modules.put("item", new ShowItemModule());
//        modules.put("i", new ShowItemModule());
        modules.put("player", new PlayerInfoModule());
    }

    public static @Nonnull Component handleMessage(@Nullable Player sender, @Nonnull String message, List<SimplePlayer> receivers) {
        if(sender == null) return Component.text(message);
        StringBuilder current = new StringBuilder();
        Component result = Component.empty();

        outerFor: for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if(c == '@'){
                if(Channel.getPlayerChannel(sender) != null) {
                    List<SimplePlayer> players = new ArrayList<>(receivers);
                    players.sort(Comparator.comparingInt((SimplePlayer p) -> p.getName().length()));
                    outer:
                    for (SimplePlayer p : players) {
                        for (int j = 0; j < p.getName().length(); j++) {
                            if (i + j + 1 >= message.length() || message.charAt(i + j + 1) != p.getName().charAt(j)) {
                                continue outer;
                            }
                        }
                        result = result.append(Component.text(current.toString()));
                        result = result.append(MentionModule.mention(p, sender));
                        current = new StringBuilder();
                        i=i+p.getName().length();
                        continue outerFor;
                    }
                }
            } else if(c == '[') {
                result = result.append(Component.text(current.toString()));
                current = new StringBuilder();
            } else if(c == ']' && current.charAt(0) == '[') {
                String moduleName = current.substring(1);
                if(modules.containsKey(moduleName)){
                    result = result.append(modules.get(moduleName).handle(sender, receivers));
                }else{
                    result = result.append(Component.text("[" + moduleName + "]"));
                }
                current.append(c);
                current = new StringBuilder();
                continue;
            }
            current.append(c);
        }
        if(!current.isEmpty()){
            result = result.append(Component.text(current.toString()));
        }
        return result.color(NamedTextColor.WHITE);
    }

    public static void notifyMentions(@Nullable Player sender, @Nonnull String message, List<SimplePlayer> receivers) {
        if (sender == null) return;
        for (SimplePlayer receiver : receivers) {
            if (message.contains("@" + receiver.getName())) {
                MentionModule.mention(receiver, sender);
            }
        }
    }
}
