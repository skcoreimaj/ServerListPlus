/*
 *        _____                     __    _     _   _____ _
 *       |   __|___ ___ _ _ ___ ___|  |  |_|___| |_|  _  | |_ _ ___
 *       |__   | -_|  _| | | -_|  _|  |__| |_ -|  _|   __| | | |_ -|
 *       |_____|___|_|  \_/|___|_| |_____|_|___|_| |__|  |_|___|___|
 *
 *  ServerListPlus - Customize your complete server status ping!
 *  Copyright (C) 2014, Minecrell <https://github.com/Minecrell>
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.minecrell.serverlistplus.bukkit;

import net.minecrell.serverlistplus.core.ServerListPlusCore;
import net.minecrell.serverlistplus.core.ServerListPlusException;
import net.minecrell.serverlistplus.core.config.PluginConf;
import net.minecrell.serverlistplus.core.favicon.FaviconHelper;
import net.minecrell.serverlistplus.core.favicon.FaviconSource;
import net.minecrell.serverlistplus.core.player.PlayerIdentity;
import net.minecrell.serverlistplus.core.plugin.ServerListPlusPlugin;
import net.minecrell.serverlistplus.core.plugin.ServerType;
import net.minecrell.serverlistplus.core.status.PlayerFetcher;
import net.minecrell.serverlistplus.core.status.StatusManager;
import net.minecrell.serverlistplus.core.status.StatusResponse;
import net.minecrell.serverlistplus.core.util.Helper;
import net.minecrell.serverlistplus.core.util.InstanceStorage;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;

import com.google.common.base.Optional;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheBuilderSpec;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.comphenix.protocol.wrappers.WrappedServerPing;
import org.mcstats.MetricsLite;

import static net.minecrell.serverlistplus.core.logging.Logger.DEBUG;
import static net.minecrell.serverlistplus.core.logging.Logger.ERROR;
import static net.minecrell.serverlistplus.core.logging.Logger.INFO;

public class BukkitPlugin extends BukkitPluginBase implements ServerListPlusPlugin {
    private final boolean spigot;

    public BukkitPlugin() {
        // Check if server is running Spigot
        boolean spigot = false;
        try {
            Class.forName("org.spigotmc.SpigotConfig");
            spigot = true;
        } catch (ClassNotFoundException ignored) {}

        this.spigot = spigot;
    }

    private ServerListPlusCore core;
    private LoadingCache<FaviconSource, Optional<WrappedServerPing.CompressedImage>> faviconCache;

    private Listener loginListener;
    private StatusPacketListener packetListener;

    private MetricsLite metrics;

    @Override
    public void onEnable() {
        try { // Load the core first
            this.core = new ServerListPlusCore(this);
            getLogger().log(INFO, "Successfully loaded!");
        } catch (ServerListPlusException e) {
            getLogger().log(INFO, "Please fix the error before restarting the server!");
            disablePlugin(); return; // Disable plugin to show error in /plugins
        } catch (Exception e) {
            getLogger().log(ERROR, "An internal error occurred while loading the core!", e);
            disablePlugin(); return; // Disable plugin to show error in /plugins
        }

        // Register commands
        getCommand("serverlistplus").setExecutor(new ServerListPlusCommand());
        getLogger().info(getDisplayName() + " enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info(getDisplayName() + " disabled.");
        // BungeeCord closes the log handlers automatically, but Bukkit does not
        for (Handler handler : getLogger().getHandlers())
            handler.close();
    }

    // Commands
    public final class ServerListPlusCommand implements TabExecutor {
        private ServerListPlusCommand() {}

        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            core.executeCommand(new BukkitCommandSender(sender), cmd.getName(), args); return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
            return core.tabComplete(new BukkitCommandSender(sender), cmd.getName(), args);
        }
    }

    // Player tracking
    public final class LoginListener implements Listener {
        private LoginListener() {}

        @EventHandler
        public void onPlayerLogin(AsyncPlayerPreLoginEvent event) {
            core.addClient(event.getAddress(), new PlayerIdentity(event.getUniqueId(), event.getName()));
        }
    }

    public final class OfflineModeLoginListener implements Listener {
        private OfflineModeLoginListener() {}

        @EventHandler
        public void onPlayerLogin(PlayerLoginEvent event) {
            core.addClient(event.getAddress(), new PlayerIdentity(event.getPlayer().getUniqueId(),
                    event.getPlayer().getName()));
        }
    }

    // Status packet listener (ProtocolLib)
    public final class StatusPacketListener extends PacketAdapter {
        public StatusPacketListener() {
            super(PacketAdapter.params(BukkitPlugin.this, PacketType.Status.Server.OUT_SERVER_INFO).optionAsync());
        }

        @Override // Server status packet
        public void onPacketSending(final PacketEvent event) {
            final WrappedServerPing ping = event.getPacket().getServerPings().read(0);
            // Make sure players have not been hidden when getting the player count
            boolean playersVisible = ping.isPlayersVisible();

            StatusResponse response = core.getRequest(event.getPlayer().getAddress().getAddress())
                    .createResponse(core.getStatus(),
                            // Return unknown player counts if it has been hidden
                            !playersVisible ? new PlayerFetcher.Hidden() :
                                    new PlayerFetcher() {
                                        @Override
                                        public Integer getOnlinePlayers() {
                                            return ping.getPlayersOnline();
                                        }

                                        @Override
                                        public Integer getMaxPlayers() {
                                            return ping.getPlayersMaximum();
                                        }
                                    });

            // Description
            String message = response.getDescription();
            if (message != null) ping.setMotD(message);

            // Version name
            message = response.getVersion();
            if (message != null) ping.setVersionName(message);
            // Protocol version
            Integer protocol = response.getProtocol();
            if (protocol != null) ping.setVersionProtocol(protocol);

            // Favicon
            FaviconSource favicon = response.getFavicon();
            if (favicon != null) {
                Optional<WrappedServerPing.CompressedImage> icon = faviconCache.getUnchecked(favicon);
                if (icon.isPresent()) ping.setFavicon(icon.get());
            }

            if (playersVisible) {
                if (response.hidePlayers()) {
                    ping.setPlayersVisible(false);
                } else {
                    // Online players
                    Integer count = response.getOnlinePlayers();
                    if (count != null) ping.setPlayersOnline(count);
                    // Max players
                    count = response.getMaxPlayers();
                    if (count != null) ping.setPlayersMaximum(count);

                    // Player hover
                    message = response.getPlayerHover();
                    if (message != null) ping.setPlayers(Collections.singleton(
                            new WrappedGameProfile(StatusManager.EMPTY_UUID, message)));
                }
            }
        }
    }

    @Override
    public ServerType getServerType() {
        return spigot ? ServerType.SPIGOT : ServerType.BUKKIT;
    }

    @Override
    public String getServerImplementation() {
        return getServer().getVersion();
    }

    @Override
    public String getRandomPlayer() {
        Player player = Helper.nextEntry(getServer().getOnlinePlayers());
        return player != null ? player.getName() : null;
    }

    @Override
    public Integer getOnlinePlayersAt(String location) {
        World world = getServer().getWorld(location);
        return world != null ? world.getPlayers().size() : null;
    }


    @Override
    public LoadingCache<FaviconSource, Optional<WrappedServerPing.CompressedImage>> getFaviconCache() {
        return faviconCache;
    }

    @Override
    public String colorize(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    @Override
    public void initialize(ServerListPlusCore core) {

    }

    @Override
    public void reloadFaviconCache(CacheBuilderSpec spec) {
        if (spec != null) {
            this.faviconCache = CacheBuilder.from(spec).build(new CacheLoader<FaviconSource,
                    Optional<WrappedServerPing.CompressedImage>>() {
                @Override
                public Optional<WrappedServerPing.CompressedImage> load(FaviconSource source) throws Exception {
                    // Try loading the favicon
                    BufferedImage image = FaviconHelper.loadSafely(core, source);
                    if (image == null) return Optional.absent(); // Favicon loading failed
                    else return Optional.of(WrappedServerPing.CompressedImage.fromPng(image)); // Success!
                }
            });
        } else {
            // Delete favicon cache
            faviconCache.invalidateAll();
            faviconCache.cleanUp();
            this.faviconCache = null;
        }
    }

    @Override
    public void configChanged(InstanceStorage<Object> confs) {
        // Player tracking
        if (confs.get(PluginConf.class).PlayerTracking) {
            if (loginListener == null) {
                registerListener(this.loginListener = spigot || getServer().getOnlineMode()
                        ? new LoginListener() : new OfflineModeLoginListener());
                getLogger().log(DEBUG, "Registered player tracking listener.");
            }
        } else if (loginListener != null) {
            unregisterListener(loginListener);
            this.loginListener = null;
            getLogger().log(DEBUG, "Unregistered player tracking listener.");
        }

        // Plugin statistics
        if (confs.get(PluginConf.class).Stats) {
            if (metrics == null)
                try {
                    this.metrics = new MetricsLite(this);
                    metrics.enable();
                    metrics.start();
                } catch (Throwable e) {
                    getLogger().log(DEBUG, "Failed to enable plugin statistics: " + Helper.causedError(e));
                }
        } else if (metrics != null)
            try {
                metrics.disable();
                this.metrics = null;
            } catch (Throwable e) {
                getLogger().log(DEBUG, "Failed to disable plugin statistics: " + Helper.causedError(e));
            }
    }

    @Override
    public void statusChanged(StatusManager status) {
        // Status packet listener
        if (status.hasChanges()) {
            if (packetListener == null) {
                ProtocolLibrary.getProtocolManager().addPacketListener(
                        this.packetListener = new StatusPacketListener());
                getLogger().log(DEBUG, "Registered status packet listener.");
            }
        } else if (packetListener != null) {
            ProtocolLibrary.getProtocolManager().removePacketListener(packetListener);
            this.packetListener = null;
            getLogger().log(DEBUG, "Unregistered status packet listener.");
        }
    }
}
