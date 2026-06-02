package pl.dowimixworsafe.betterlistintegration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BetterListPluginMessageListener implements PluginMessageListener {

    private final PartyManager partyManager;
    private final Logger logger;

    public BetterListPluginMessageListener(PartyManager partyManager, Logger logger) {
        this.partyManager = partyManager;
        this.logger = logger;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals("betterlist:sync")) return;

        try {
            String jsonString;
            if (message.length > 0 && message[0] == '{') {
                // Raw UTF-8 JSON (no framing)
                jsonString = new String(message, StandardCharsets.UTF_8);
            } else {
                // VarInt-prefixed string (Fabric PacketByteBuf.writeString)
                ByteBuffer buf = ByteBuffer.wrap(message);
                int length = readVarInt(buf);
                if (length < 0 || length > buf.remaining()) return; // malformed framing
                byte[] jsonBytes = new byte[length];
                buf.get(jsonBytes);
                jsonString = new String(jsonBytes, StandardCharsets.UTF_8);
            }

            JsonReader jsonReader = new JsonReader(new StringReader(jsonString));
            jsonReader.setStrictness(Strictness.LENIENT);
            JsonObject json = JsonParser.parseReader(jsonReader).getAsJsonObject();

            if (json == null || !json.has("type")) return;

            String type = json.get("type").getAsString();

            if ("BML_HELLO".equals(type)) {
                JsonObject ack = new JsonObject();
                ack.addProperty("type", "BML_HELLO_ACK");
                ack.addProperty("version", "2");
                partyManager.sendPacket(player, "betterlist:sync", ack);
                return;
            }

            if (type.toUpperCase().startsWith("PARTY_")) {
                handlePartyPacket(player, type.toLowerCase(), json);
            } else {
                handleSyncPacket(player, json);
            }

        } catch (Exception e) {
            logger.log(Level.WARNING, "[BetterList] Failed to handle a betterlist:sync message: " + e.getMessage());
        }
    }

    /** Safe UUID parse from a JSON field; returns null on a missing/invalid value. */
    private UUID parseUuid(JsonObject json, String field) {
        if (json == null || !json.has(field)) return null;
        try {
            return UUID.fromString(json.get(field).getAsString());
        } catch (Exception e) {
            return null;
        }
    }

    /** Reads a VarInt; returns -1 on a malformed/too-large value instead of throwing. */
    private int readVarInt(ByteBuffer buf) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (!buf.hasRemaining()) return -1;
            int b = buf.get() & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
        }
        return -1; // VarInt too large
    }

    private void handlePartyPacket(Player player, String type, JsonObject json) {
        switch (type) {
            case "party_invite":
                handleInvite(player, json);
                break;
            case "party_accept":
                handleAccept(player, json);
                break;
            case "party_leave":
                handleLeave(player, json);
                break;
            case "party_kick":
                handleKick(player, json);
                break;
        }
    }

    private void handleInvite(Player sender, JsonObject json) {
        UUID partyId = parseUuid(json, "partyId");
        if (partyId == null || !json.has("targetNick")) return;

        String targetNick = json.get("targetNick").getAsString();
        Player targetPlayer = Bukkit.getPlayerExact(targetNick);

        if (targetPlayer == null) {
            partyManager.sendError(sender, "That player is not online!");
            return;
        }

        if (targetPlayer.getUniqueId().equals(sender.getUniqueId())) {
            partyManager.sendError(sender, "You can't invite yourself.");
            return;
        }

        if (partyManager.isPlayerInAnyParty(targetPlayer.getUniqueId())) {
            partyManager.sendError(sender, "That player is already in another party!");
            return;
        }

        if (!partyManager.partyExists(partyId)) {
            // New party: the sender must not already belong to another party.
            if (partyManager.isPlayerInAnyParty(sender.getUniqueId())) {
                partyManager.sendError(sender, "You already belong to another party.");
                return;
            }
            partyManager.createParty(partyId, sender.getUniqueId());
            partyManager.broadcastPartyUpdate(partyId);
        } else if (!partyManager.isLeader(partyId, sender.getUniqueId())) {
            partyManager.sendError(sender, "Only the party leader can invite!");
            return;
        }

        // Record the invite so the target can later accept this specific party.
        partyManager.addInvite(partyId, targetPlayer.getUniqueId());

        JsonObject invitePkt = new JsonObject();
        invitePkt.addProperty("type", "PARTY_INVITE_NOTIFY");
        invitePkt.addProperty("partyId", partyId.toString());
        invitePkt.addProperty("fromNick", sender.getName());

        partyManager.sendPacket(targetPlayer, "betterlist:sync", invitePkt);
        sender.sendMessage("§a[BetterList] Invite sent to " + targetPlayer.getName());
    }

    private void handleAccept(Player acceptingPlayer, JsonObject json) {
        UUID partyId = parseUuid(json, "partyId");
        if (partyId == null) return;

        if (!partyManager.partyExists(partyId)) {
            partyManager.sendError(acceptingPlayer, "That party no longer exists!");
            return;
        }

        // A player may only accept a party they were actually invited to.
        if (!partyManager.hasInvite(partyId, acceptingPlayer.getUniqueId())) {
            partyManager.sendError(acceptingPlayer, "You have no pending invite to that party.");
            return;
        }

        if (partyManager.isPlayerInAnyParty(acceptingPlayer.getUniqueId())) {
            partyManager.sendError(acceptingPlayer, "Leave your current party first!");
            return;
        }

        partyManager.removeInvite(partyId, acceptingPlayer.getUniqueId());
        partyManager.addPlayerToParty(partyId, acceptingPlayer.getUniqueId());
        partyManager.broadcastPartyUpdate(partyId);
    }

    private void handleLeave(Player leavingPlayer, JsonObject json) {
        UUID playerUUID = leavingPlayer.getUniqueId();
        UUID partyId = partyManager.getPartyIdForPlayer(playerUUID);

        if (partyId != null) {
            if (partyManager.isLeader(partyId, playerUUID)) {
                partyManager.broadcastDisband(partyId);
                partyManager.disbandParty(partyId);
            } else {
                partyManager.removePlayerFromParty(partyId, playerUUID);
                JsonObject leaveAck = new JsonObject();
                leaveAck.addProperty("type", "PARTY_LEAVE");
                leaveAck.addProperty("partyId", partyId.toString());
                partyManager.sendPacket(leavingPlayer, "betterlist:sync", leaveAck);
                partyManager.broadcastPartyUpdate(partyId);
            }
        }
    }

    private void handleKick(Player sender, JsonObject json) {
        UUID partyId = parseUuid(json, "partyId");
        if (partyId == null || !json.has("targetNick")) return;
        String targetNick = json.get("targetNick").getAsString();
        Player targetPlayer = Bukkit.getPlayerExact(targetNick);

        if (!partyManager.isLeader(partyId, sender.getUniqueId())) {
            partyManager.sendError(sender, "Only the party leader can kick players!");
            return;
        }

        if (targetPlayer != null && partyManager.getPartyMembers(partyId).contains(targetPlayer.getUniqueId())) {
            partyManager.removePlayerFromParty(partyId, targetPlayer.getUniqueId());

            JsonObject kickPkt = new JsonObject();
            kickPkt.addProperty("type", "PARTY_LEAVE");
            kickPkt.addProperty("partyId", partyId.toString());
            partyManager.sendPacket(targetPlayer, "betterlist:sync", kickPkt);
            targetPlayer.sendMessage("§c[BetterList] You were kicked from the party.");

            partyManager.broadcastPartyUpdate(partyId);
        } else {
            partyManager.sendError(sender, "That player is not in your party!");
        }
    }

    private void handleSyncPacket(Player player, JsonObject json) {
        UUID partyId = partyManager.getPartyIdForPlayer(player.getUniqueId());
        if (partyId == null) return;

        String type = json.get("type").getAsString();

        if (type.equals("SYNC_PLACEMENT_REQUEST")) {
            if (!json.has("targetNick")) return;
            String targetNick = json.get("targetNick").getAsString();
            json.addProperty("requestingNick", player.getName());
            for (UUID memberUUID : partyManager.getPartyMembers(partyId)) {
                Player p = Bukkit.getPlayer(memberUUID);
                if (p != null && p.getName().equalsIgnoreCase(targetNick)) {
                    partyManager.sendPacket(p, "betterlist:sync", json);
                    break;
                }
            }
            return;
        }

        if (type.equals("SYNC_PLACEMENT")) {
            if (json.has("targetNick")) {
                String targetNick = json.get("targetNick").getAsString();
                for (UUID memberUUID : partyManager.getPartyMembers(partyId)) {
                    Player p = Bukkit.getPlayer(memberUUID);
                    if (p != null && p.getName().equalsIgnoreCase(targetNick)) {
                        partyManager.sendPacket(p, "betterlist:sync", json);
                        break;
                    }
                }
                return;
            }
        }

        for (UUID memberUUID : partyManager.getPartyMembers(partyId)) {
            if (!memberUUID.equals(player.getUniqueId())) {
                Player p = Bukkit.getPlayer(memberUUID);
                if (p != null) {
                    partyManager.sendPacket(p, "betterlist:sync", json);
                }
            }
        }
    }
}
