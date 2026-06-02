package pl.dowimixworsafe.betterlistintegration;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class BetterListIntegration extends JavaPlugin {

    private PartyManager partyManager;

    @Override
    public void onEnable() {
        this.partyManager = new PartyManager(this);

        // Register the plugin messaging channels (outgoing/incoming).
        getServer().getMessenger().registerOutgoingPluginChannel(this, "betterlist:sync");

        BetterListPluginMessageListener messageListener = new BetterListPluginMessageListener(partyManager, getLogger());
        getServer().getMessenger().registerIncomingPluginChannel(this, "betterlist:sync", messageListener);

        // Register Bukkit event listeners.
        Bukkit.getPluginManager().registerEvents(new PlayerQuitListener(partyManager), this);

        getLogger().info("Better List Integration enabled. Listening on the betterlist:sync channel.");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
    }
}
