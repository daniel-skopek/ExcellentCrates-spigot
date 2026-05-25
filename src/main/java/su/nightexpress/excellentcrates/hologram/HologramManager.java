package su.nightexpress.excellentcrates.hologram;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.config.Config;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.hologram.entity.FakeDisplay;
import su.nightexpress.excellentcrates.hologram.entity.FakeEntity;
import su.nightexpress.excellentcrates.hologram.entity.FakeEntityGroup;
import su.nightexpress.excellentcrates.hologram.handler.HologramPacketsHandler;
import su.nightexpress.excellentcrates.hologram.handler.HologramProtocolHandler;
import su.nightexpress.excellentcrates.hologram.listener.HologramListener;
import su.nightexpress.excellentcrates.hooks.HookId;
import su.nightexpress.excellentcrates.util.CrateUtils;
import su.nightexpress.excellentcrates.util.pos.WorldPos;
import su.nightexpress.nightcore.manager.AbstractManager;
import su.nightexpress.nightcore.util.LocationUtil;
import su.nightexpress.nightcore.util.Plugins;
import su.nightexpress.nightcore.util.placeholder.Replacer;

import java.util.*;

public class HologramManager extends AbstractManager<CratesPlugin> {

    private final Map<String, FakeDisplay> displayMap;

    private HologramHandler handler;

    public HologramManager(@NotNull CratesPlugin plugin) {
        super(plugin);
        this.displayMap = new HashMap<>();
    }

    @Override
    protected void onLoad() {
        if (this.detectHandler()) {
            this.addListener(new HologramListener(this.plugin, this));

            this.addTask(this::tickHolograms, Config.CRATE_HOLOGRAM_UPDATE_INTERVAL.get());
        }
    }

    @Override
    protected void onShutdown() {
        this.displayMap.values().forEach(this::discard);
        this.displayMap.clear();

        this.handler = null;
    }

    private boolean detectHandler() {
        if (Plugins.isInstalled(HookId.PACKET_EVENTS)) {
            this.handler = new HologramPacketsHandler();
        }
        else if (Plugins.isInstalled(HookId.PROTOCOL_LIB)) {
            this.handler = new HologramProtocolHandler();
        }
        else {
            this.plugin.warn("*".repeat(25));
            this.plugin.warn("You have no packet library plugins installed for the Holograms feature to work.");
            this.plugin.warn("Please install one of the following plugins to enable crate holograms: " + HookId.PACKET_EVENTS + " or " + HookId.PROTOCOL_LIB);
            this.plugin.warn("*".repeat(25));
        }

        return this.hasHandler();
    }

    private void tickHolograms() {
        this.plugin.getCrateManager().getCrates().forEach(crate -> {
            if (!crate.isHologramEnabled()) return;

            try {
                this.render(crate);
            }
            catch (Exception exception) {
                this.plugin.error("Could not render hologram for '" + crate.getId() + "' crate!");
                exception.printStackTrace();
            }
        });
    }

    public boolean hasHandler() {
        return this.handler != null;
    }

    @Nullable
    private FakeDisplay getDisplay(@NotNull Crate crate) {
        return this.displayMap.get(crate.getId());
    }

    public void disableBlockHologram(@NotNull Crate crate, @NotNull WorldPos blockPos) {
        this.toggleBlockHologram(crate, blockPos, false);
    }

    public void enableBlockHologram(@NotNull Crate crate, @NotNull WorldPos blockPos) {
        this.toggleBlockHologram(crate, blockPos, true);
    }

    private void toggleBlockHologram(@NotNull Crate crate, @NotNull WorldPos blockPos, boolean enabled) {
        FakeDisplay display = this.getDisplay(crate);
        if (display == null) return;

        FakeEntityGroup group = display.getGroup(blockPos);
        if (group == null) return;

        group.setDisabled(!enabled);

        if (group.isDisabled()) {
            this.discard(group);
        }
        else {
            this.render(crate);
        }
    }



    public void removeForViewer(@NotNull Player player) {
        this.displayMap.values().forEach(display -> this.removeForViewer(player, display));
    }

    public void removeForViewer(@NotNull Player player, @NotNull FakeDisplay display) {
        display.getGroups().forEach(group -> this.removeForViewer(player, group));
    }

    public void removeForViewer(@NotNull Player player, @NotNull FakeEntityGroup group) {
        group.removeViewer(player);
        this.handler.sendDestroyEntityPacket(player, group.getEntityIDs());
    }



    public void discard(@NotNull Crate crate) {
        FakeDisplay display = this.displayMap.remove(crate.getId());
        if (display == null) return;

        this.discard(display);
    }

    public void discard(@NotNull FakeDisplay display) {
        display.getGroups().forEach(this::discard);
    }

    public void discard(@NotNull FakeEntityGroup group) {
        group.clearViewers();
        this.handler.sendDestroyEntityPacket(group.getEntityIDs());
    }



    public void render(@NotNull Crate crate) {
        this.createIfAbsent(crate);

        FakeDisplay display = this.getDisplay(crate);
        if (display == null) return;

        if (display.getGroups().isEmpty()) {
            this.displayMap.remove(crate.getId());
            return;
        }

        List<String> text = Replacer.create().replace(crate.replacePlaceholders()).apply(crate.getHologramText().reversed());
        if (text.isEmpty()) return;

        for (FakeEntityGroup group : display.getGroups()) {
            if (group.isDisabled()) continue;

            WorldPos blockPosition = group.getBlockPosition();
            World world = blockPosition.getWorld();
            Location location = blockPosition.toLocation();

            if (!blockPosition.isChunkLoaded() || world == null || location == null) {
                this.discard(group); // Remove all viewers and send entity destroy packet.
                continue;
            }

            List<Player> players;
            try {
                players = new ArrayList<>(world.getPlayers());
            }
            catch (Exception exception) {
                this.plugin.error("Could not get players from world '" + world.getName() + "' for hologram rendering!");
                exception.printStackTrace();
                continue;
            }

            players.removeIf(player -> {
                if (CrateUtils.isInEffectRange(player, location)) return false;

                this.removeForViewer(player, group);
                return true;
            });

            if (players.isEmpty()) {
                this.discard(group);
                continue;
            }

            players.forEach(player -> {
                try {
                    boolean needSpawn = !group.isViewer(player);

                    List<String> hologramText = Replacer.create().replacePlaceholderAPI(player).apply(text);
                    List<FakeEntity> holograms = group.getEntities();
                    for (int index = 0; index < holograms.size(); index++) {
                        String line = index >= hologramText.size() ? "" : hologramText.get(index);
                        FakeEntity entity = holograms.get(index);
                        this.handler.sendHologramPackets(player, entity, needSpawn, line);
                    }

                    group.addViewer(player);
                }
                catch (Exception exception) {
                    this.plugin.error("Could not send hologram packets for player '" + player.getName() + "'!");
                    exception.printStackTrace();
                }
            });
        }
    }

    private void createIfAbsent(@NotNull Crate crate) {
        if (!this.hasHandler()) return;
        if (this.displayMap.containsKey(crate.getId())) return;

        List<String> originText = crate.getHologramText();
        if (originText.isEmpty()) return;

        FakeDisplay display = new FakeDisplay();

        double yOffset = crate.getHologramYOffset() + 0.2;
        double lineGap = Config.CRATE_HOLOGRAM_LINE_GAP.get();

        crate.getBlockPositions().forEach(blockPos -> {
            try {
                World world = blockPos.getWorld();
                if (world == null) return;

                double blockHeight = 1.0;
                double height = blockHeight / 2D + yOffset;

                FakeEntityGroup group = display.getGroupOrCreate(blockPos);

                for (int index = 0; index < originText.size(); index++) {
                    double gap = lineGap * index;

                    Location location = LocationUtil.setCenter3D(new Location(world, blockPos.getX(), blockPos.getY(), blockPos.getZ())).add(0, height + gap, 0);
                    group.addEntity(FakeEntity.create(location));
                }
            }
            catch (Exception exception) {
                this.plugin.error("Could not create hologram for block at '" + blockPos + "'!");
                exception.printStackTrace();
            }
        });

        if (display.getGroups().isEmpty()) return;

        this.displayMap.put(crate.getId(), display);
    }
}
