package io.github.agentrkid.nametag

import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.events.PacketContainer
import com.google.common.collect.ImmutableList
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.lang.Exception
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors
import kotlin.collections.ArrayList

// TODO: Add visibility checks ??
class NameTagAPI : JavaPlugin(), Listener {
    companion object {
        lateinit var instance: NameTagAPI
        private const val NAME_TAG_TEAM_PREFIX = "NameTag-"
    }

    private var providers: MutableList<NameTagProvider> = ArrayList()
    private val cachedTeams: MutableMap<UUID, MutableList<String>> = ConcurrentHashMap()

    override fun onEnable() {
        instance = this

        Bukkit.getPluginManager().registerEvents(this, this)
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        Bukkit.getScheduler().runTaskLater(this, {
            val player = event.player
            cachedTeams[player.uniqueId] = ArrayList()
            reloadNameTag(player)
            reloadAllNameTagsAsync(player)
        }, 20)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        cachedTeams.remove(event.player.uniqueId)
    }

    fun registerProvider(provider: NameTagProvider) {
        providers.add(provider)
        providers = providers.stream().sorted { o1, o2 ->  o2.weight - o1.weight }.collect(Collectors.toList())
    }

    fun reloadNameTag(player: Player) {
        if (providers.isEmpty()) {
            return
        }

        for (onlinePlayer in Bukkit.getOnlinePlayers()) {
            reloadNameTagAsync(player, onlinePlayer)
        }
    }

    fun reloadNameTagAsync(player: Player, other: Player) {
        Bukkit.getScheduler().runTaskAsynchronously(this) {
            reloadNameTag(player, other)
        }
    }

    fun reloadNameTag(player: Player, other: Player) {
        if (providers.isEmpty()) {
            return
        }

        val provider = providers[0]

        reloadNameTag(NameTagUpdate(player, other, provider.fetchNameTag(player, other)))
    }

    fun reloadAllNameTagsAsync(player: Player) {
        Bukkit.getScheduler().runTaskAsynchronously(this) {
            reloadAllNameTags(player)
        }
    }

    fun reloadAllNameTags(player: Player) {
        if (providers.isEmpty()) {
            return
        }

        val provider = providers[0]

        for (onlinePlayer in Bukkit.getOnlinePlayers()) {
            if (onlinePlayer == player) {
                continue
            }

            reloadNameTag(NameTagUpdate(onlinePlayer, player, provider.fetchNameTag(onlinePlayer, player)))
        }
    }

    private fun reloadNameTag(update: NameTagUpdate) {
        val refreshFor = update.refreshFor
        val toRefresh = update.toRefresh
        val nameTag = update.nameTag

        var teams = cachedTeams[refreshFor.uniqueId]

        if (teams == null) {
            teams = ArrayList()
        }

        val team = NAME_TAG_TEAM_PREFIX + toRefresh.uniqueId.toString().substring(28)
        if (!teams.contains(team)) {
            teams.add(team)
            createTeam(refreshFor, team, toRefresh.name)
        }

        val teamUpdatePacket = PacketContainer(PacketType.Play.Server.SCOREBOARD_TEAM)

        // Set both 0 and 1 to the team name because why not
        // and to be honest it has issues without both of them being set
        teamUpdatePacket.strings.write(0, team)
        teamUpdatePacket.strings.write(1, team)
        teamUpdatePacket.strings.write(2, nameTag.prefix)
        teamUpdatePacket.strings.write(3, nameTag.suffix)
        teamUpdatePacket.integers.write(1, 2)

        sendPacket(refreshFor, teamUpdatePacket)
    }

    private fun createTeam(player: Player, team: String, playerName: String) {
        val createTeamPacket = PacketContainer(PacketType.Play.Server.SCOREBOARD_TEAM)

        // Check out line 124-125
        createTeamPacket.strings.write(0, team)
        createTeamPacket.strings.write(1, team)

        // Add the player who's NameTag is being shown/created
        createTeamPacket.getSpecificModifier(Collection::class.java).write(0, ImmutableList.of(playerName))

        sendPacket(player, createTeamPacket)
    }

    private fun sendPacket(player: Player, packet: PacketContainer) {
        try {
            ProtocolLibrary.getProtocolManager().sendServerPacket(player, packet)
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    class NameTagUpdate(val toRefresh: Player, val refreshFor: Player, val nameTag: NameTag) {
        class NameTag(val prefix: String, val suffix: String)
    }

    abstract class NameTagProvider(val weight: Int) {
        abstract fun fetchNameTag(toRefresh: Player, refreshFor: Player): NameTagUpdate.NameTag

        fun createNameTag(prefix: String, suffix: String): NameTagUpdate.NameTag {
            return NameTagUpdate.NameTag(ChatColor.translateAlternateColorCodes('&', prefix), ChatColor.translateAlternateColorCodes('&', suffix))
        }
    }
}