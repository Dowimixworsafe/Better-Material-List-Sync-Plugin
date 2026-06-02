package pl.dowimixworsafe.betterlistintegration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PartyManager {

    private final JavaPlugin plugin;
    private final Map<UUID, Set<UUID>> parties = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> partyLeaders = new ConcurrentHashMap<>();
    // partyId -> set of player UUIDs who have an outstanding invite to that party.
    // A player may only accept a party they were actually invited to.
    private final Map<UUID, Set<UUID>> pendingInvites = new ConcurrentHashMap<>();

    public PartyManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // --- Invites ---------------------------------------------------------------

    public void addInvite(UUID partyId, UUID playerUUID) {
        pendingInvites.computeIfAbsent(partyId, k -> ConcurrentHashMap.newKeySet()).add(playerUUID);
    }

    public boolean hasInvite(UUID partyId, UUID playerUUID) {
        Set<UUID> invited = pendingInvites.get(partyId);
        return invited != null && invited.contains(playerUUID);
    }

    public void removeInvite(UUID partyId, UUID playerUUID) {
        Set<UUID> invited = pendingInvites.get(partyId);
        if (invited != null) {
            invited.remove(playerUUID);
            if (invited.isEmpty()) pendingInvites.remove(partyId);
        }
    }

    public void createParty(UUID partyId, UUID adminUUID) {
        Set<UUID> members = ConcurrentHashMap.newKeySet();
        members.add(adminUUID);
        parties.put(partyId, members);
        partyLeaders.put(partyId, adminUUID);
    }

    public void addPlayerToParty(UUID partyId, UUID playerUUID) {
        parties.computeIfAbsent(partyId, k -> ConcurrentHashMap.newKeySet()).add(playerUUID);
    }

    public void removePlayerFromParty(UUID partyId, UUID playerUUID) {
        Set<UUID> members = parties.get(partyId);
        if (members != null) {
            members.remove(playerUUID);
            if (members.isEmpty()) {
                parties.remove(partyId);
                partyLeaders.remove(partyId);
                pendingInvites.remove(partyId);
            }
        }
    }

    public Set<UUID> getPartyMembers(UUID partyId) {
        return parties.getOrDefault(partyId, Collections.emptySet());
    }

    public UUID getLeader(UUID partyId) {
        return partyLeaders.get(partyId);
    }

    public boolean isLeader(UUID partyId, UUID playerUUID) {
        UUID leader = partyLeaders.get(partyId);
        return leader != null && leader.equals(playerUUID);
    }

    public boolean partyExists(UUID partyId) {
        return parties.containsKey(partyId);
    }

    public void disbandParty(UUID partyId) {
        parties.remove(partyId);
        partyLeaders.remove(partyId);
        pendingInvites.remove(partyId);
    }

    public boolean isPlayerInAnyParty(UUID playerUUID) {
        return getPartyIdForPlayer(playerUUID) != null;
    }

    public UUID getPartyIdForPlayer(UUID playerUUID) {
        for (Map.Entry<UUID, Set<UUID>> entry : parties.entrySet()) {
            if (entry.getValue().contains(playerUUID)) {
                return entry.getKey();
            }
        }
        return null;
    }

    // --- Broadcast utils -------------------------------------------------------

    /** Sends a member-list update to every online player in the party. */
    public void broadcastPartyUpdate(UUID partyId) {
        Set<UUID> members = getPartyMembers(partyId);
        if (members.isEmpty()) return;

        UUID leader = getLeader(partyId);
        String adminNick = leader != null && Bukkit.getPlayer(leader) != null ? Bukkit.getPlayer(leader).getName() : "";

        JsonObject json = new JsonObject();
        json.addProperty("type", "PARTY_UPDATE");
        json.addProperty("partyId", partyId.toString());
        json.addProperty("adminNick", adminNick);

        JsonArray membersArray = new JsonArray();
        for (UUID memberUUID : members) {
            Player p = Bukkit.getPlayer(memberUUID);
            if (p != null && p.isOnline()) {
                membersArray.add(p.getName());
            }
        }
        json.add("members", membersArray);

        broadcastToParty(partyId, "betterlist:sync", json);
    }

    /** Tells all members the party was disbanded. */
    public void broadcastDisband(UUID partyId) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "PARTY_LEAVE");
        json.addProperty("partyId", partyId.toString());
        broadcastToParty(partyId, "betterlist:sync", json);
    }

    /** Sends an error message to a specific player. */
    public void sendError(Player player, String message) {
        if (player == null || !player.isOnline()) return;
        JsonObject json = new JsonObject();
        json.addProperty("type", "PARTY_ERROR");
        json.addProperty("message", message);
        sendPacket(player, "betterlist:sync", json);
    }

    /** Sends a single packet (server -> client) on the given channel. */
    public void sendPacket(Player player, String channel, JsonObject json) {
        if (player == null || !player.isOnline()) return;
        byte[] jsonBytes = json.toString().getBytes(StandardCharsets.UTF_8);
        // Mod codec uses FriendlyByteBuf.readByteArray() which expects a VarInt length prefix
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(5 + jsonBytes.length);
        writeVarInt(buf, jsonBytes.length);
        buf.put(jsonBytes);
        buf.flip();
        byte[] payload = new byte[buf.remaining()];
        buf.get(payload);
        player.sendPluginMessage(plugin, channel, payload);
    }

    private void writeVarInt(java.nio.ByteBuffer buf, int value) {
        while (true) {
            if ((value & ~0x7F) == 0) { buf.put((byte) value); return; }
            buf.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
    }

    /** Relays a packet to all online players in the given party. */
    public void broadcastToParty(UUID partyId, String channel, JsonObject json) {
        Set<UUID> members = getPartyMembers(partyId);
        for (UUID memberUUID : members) {
            Player p = Bukkit.getPlayer(memberUUID);
            if (p != null && p.isOnline()) {
                sendPacket(p, channel, json);
            }
        }
    }
}
