package pl.dowimixworsafe.betterlistintegration;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

public class PlayerQuitListener implements Listener {

    private final PartyManager partyManager;

    public PlayerQuitListener(PartyManager partyManager) {
        this.partyManager = partyManager;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        
        UUID partyId = partyManager.getPartyIdForPlayer(playerUUID);
        if (partyId != null) {
            // If the leader left, disband the whole party.
            if (partyManager.isLeader(partyId, playerUUID)) {
                // broadcastDisband uses isOnline, so the leaving player is skipped.
                partyManager.broadcastDisband(partyId);
                partyManager.disbandParty(partyId);
            } else {
                // A regular member left: remove them and notify the rest.
                partyManager.removePlayerFromParty(partyId, playerUUID);
                partyManager.broadcastPartyUpdate(partyId);
            }
        }
    }
}
