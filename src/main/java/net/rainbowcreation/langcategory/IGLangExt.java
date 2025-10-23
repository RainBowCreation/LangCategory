package net.rainbowcreation.langcategory;

import me.icegames.iglanguages.api.TranslationExtension;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class IGLangExt implements TranslationExtension {
    private final LangCategory plugin;
    public IGLangExt(LangCategory plugin) { this.plugin = plugin; }

    @Override
    public String name() {
        return "LangCategory";
    }

    @Override
    public Plugin plugin() {
        return plugin;
    }

    @Override public Gate gate(Player player, String lang, String category, String key) {
        return plugin.decideGate(player, category) ? Gate.ALWAYS_ALLOW : Gate.ALWAYS_DENY;
    }
}