package cn.jason31416.chatx.discord;

import com.velocitypowered.api.proxy.Player;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.tablist.SortingManager;
import me.neznamy.tab.api.tablist.TabListFormatManager;

import javax.annotation.Nonnull;
import java.util.UUID;

/** Only loaded when the optional TAB plugin is present. */
final class DiscordTabIntegration {
    private DiscordTabIntegration() {}

    @Nonnull
    static DiscordTabValues values(@Nonnull Player player) {
        TabAPI api = TabAPI.getInstance();
        UUID uuid = player.getUniqueId();
        TabPlayer tabPlayer = api.getPlayer(uuid);
        if(tabPlayer == null || !tabPlayer.isLoaded()) return DiscordTabValues.EMPTY;

        TabListFormatManager format = api.getTabListFormatManager();
        SortingManager sorting = api.getSortingManager();
        String prefix = "";
        String name = player.getUsername();
        String suffix = "";
        String originalPrefix = "";
        String originalName = name;
        String originalSuffix = "";
        if(format != null){
            originalPrefix = format.getOriginalReplacedPrefix(tabPlayer);
            originalName = format.getOriginalReplacedName(tabPlayer);
            originalSuffix = format.getOriginalReplacedSuffix(tabPlayer);
            prefix = applied(format.getCustomPrefix(tabPlayer), originalPrefix);
            name = applied(format.getCustomName(tabPlayer), originalName);
            suffix = applied(format.getCustomSuffix(tabPlayer), originalSuffix);
        }
        String sortKey = "";
        if(sorting != null){
            sortKey = applied(sorting.getForcedTeamName(tabPlayer), sorting.getOriginalTeamName(tabPlayer));
        }
        return new DiscordTabValues(prefix, name, suffix, tabPlayer.getGroup(), sortKey, tabPlayer.getWorld(),
                originalPrefix, originalName, originalSuffix);
    }

    private static String applied(String custom, String original) {
        return custom == null ? original : custom;
    }

}
