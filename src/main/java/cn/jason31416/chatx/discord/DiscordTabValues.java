package cn.jason31416.chatx.discord;

record DiscordTabValues(
        String prefix,
        String name,
        String suffix,
        String group,
        String sortKey,
        String world,
        String originalPrefix,
        String originalName,
        String originalSuffix
) {
    static final DiscordTabValues EMPTY = new DiscordTabValues("", "", "", "", "", "", "", "", "");
}
