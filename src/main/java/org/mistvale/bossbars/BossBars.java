package org.mistvale.bossbars;

import org.mistvale.bossbars.commands.BossBar;

import org.powernukkitx.plugin.PluginBase;
import org.powernukkitx.plugin.annotation.PluginMeta;

@PluginMeta(name = "BossBars", version = "1.0.0", authors = { "Wimziicals" }, api = {
        "3.0.5" }, website = "https://github.com/Wimziicals/pnx-bossbars")
public class BossBars extends PluginBase {

    private static BossBars INSTANCE;

    private BossBar bossBar;

    @Override
    public void onLoad() {
        this.getLogger().info("Plugin loaded");
    }

    @Override
    public void onEnable() {
        this.getLogger().info("Plugin enabled");

        INSTANCE = this;

        this.bossBar = new BossBar(this);
        this.getServer().getCommandMap().register("mistvale", this.bossBar);
        this.getServer().getPluginManager().registerEvents(this.bossBar, this);
    }

    @Override
    public void onDisable() {
        this.getLogger().info("Plugin disabled");

        INSTANCE = this;

        if (this.bossBar != null) {
            this.bossBar.shutdown();
        }
    }

    public static BossBars get() {
        return INSTANCE;
    }
}
