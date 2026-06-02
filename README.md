# BetterList Integration

Server-side companion plugin for the **[Better List for Litematica](https://github.com/Dowimixworsafe/Better-Material-List)** Fabric mod.

It enables the mod's **party progress sync** on Bukkit-family servers (Paper, Purpur, Spigot, …). It is a thin **relay**: it tracks party membership in memory and forwards the mod's `betterlist:sync` messages between party members. It never inspects or stores the synced build data.

> You only need this plugin if your server is **Bukkit-based**. On a **Fabric** server, install the Better List mod itself server-side instead — no plugin required. Without either, all single-player features of the mod still work.

## How it works

- The mod (client) and the server talk over the plugin messaging channel `betterlist:sync`.
- The client sends a `BML_HELLO`; the plugin replies `BML_HELLO_ACK`, which switches the mod into party/sync mode.
- `PARTY_*` packets manage membership (invite / accept / leave / kick); the plugin validates them (e.g. you can only accept a party you were invited to, only the leader can invite/kick).
- All other (`SYNC_*`) packets are relayed unchanged to the other party members.

Because the plugin never parses the build payload, the mod can evolve its sync data format freely as long as the JSON field *names* stay stable.

## Build

Requires JDK 25 and Maven.

```bash
mvn clean package
```

The jar is produced in `target/` (shaded). Drop it into your server's `plugins/` folder.

## Compatibility

- **Paper** (recommended), **Purpur**, **Spigot**, and their forks/hybrids that expose the Bukkit API.
- Built against `paper-api` for Minecraft 26.1.2.

## License

All Rights Reserved.
