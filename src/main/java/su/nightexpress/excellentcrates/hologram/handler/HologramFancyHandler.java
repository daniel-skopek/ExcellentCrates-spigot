package su.nightexpress.excellentcrates.hologram.handler;

import com.fancyinnovations.fancyholograms.api.FancyHolograms;
import com.fancyinnovations.fancyholograms.api.HologramRegistry;
import com.fancyinnovations.fancyholograms.api.data.TextHologramData;
import com.fancyinnovations.fancyholograms.api.hologram.Hologram;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.config.Config;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.hologram.HologramHandler;
import su.nightexpress.excellentcrates.util.pos.WorldPos;
import su.nightexpress.nightcore.util.placeholder.Replacer;
import su.nightexpress.nightcore.util.text.night.NightMessage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HologramFancyHandler implements HologramHandler {

    private final CratesPlugin plugin;

    private final Map<String, Map<WorldPos, Hologram>> hologramMap;
    private final Map<String, Set<WorldPos>>            disabledMap;

    public HologramFancyHandler(@NotNull CratesPlugin plugin) {
        this.plugin = plugin;
        this.hologramMap = new HashMap<>();
        this.disabledMap = new HashMap<>();
    }

    @NotNull
    private HologramRegistry getRegistry() {
        return FancyHolograms.get().getRegistry();
    }

    @Override
    public void create(@NotNull Crate crate) {
        this.delete(crate);
        this.render(crate);
    }

    @Override
    public void delete(@NotNull Crate crate) {
        Map<WorldPos, Hologram> map = this.hologramMap.remove(crate.getId());
        this.disabledMap.remove(crate.getId());
        if (map == null) return;

        HologramRegistry registry = this.getRegistry();
        map.values().forEach(registry::unregister);
    }

    @Override
    public void update(@NotNull Crate crate) {
        if (!crate.isHologramEnabled()) return;

        this.render(crate);
    }

    @Override
    public void toggle(@NotNull Crate crate, @NotNull WorldPos blockPos, boolean enabled) {
        Map<WorldPos, Hologram> map = this.hologramMap.get(crate.getId());
        if (map == null) return;

        if (enabled) {
            this.disabledMap.computeIfAbsent(crate.getId(), k -> new HashSet<>()).remove(blockPos);
            this.render(crate, blockPos);
            return;
        }

        this.disabledMap.computeIfAbsent(crate.getId(), k -> new HashSet<>()).add(blockPos);

        Hologram hologram = map.remove(blockPos);
        if (hologram != null) {
            this.getRegistry().unregister(hologram);
        }
    }

    @Override
    public void purge(@NotNull Player player) {
        // FancyHolograms handles per-player visibility on its own.
    }

    private void render(@NotNull Crate crate) {
        List<String> text = this.createText(crate);
        if (text.isEmpty()) return;

        Set<WorldPos> disabled = this.disabledMap.getOrDefault(crate.getId(), Set.of());

        for (WorldPos blockPos : crate.getBlockPositions()) {
            if (disabled.contains(blockPos)) continue;

            this.render(crate, blockPos, text);
        }
    }

    private void render(@NotNull Crate crate, @NotNull WorldPos blockPos) {
        List<String> text = this.createText(crate);
        if (text.isEmpty()) return;

        this.render(crate, blockPos, text);
    }

    private void render(@NotNull Crate crate, @NotNull WorldPos blockPos, @NotNull List<String> text) {
        Location location = blockPos.toLocation();
        if (location == null) return;

        Map<WorldPos, Hologram> map = this.hologramMap.computeIfAbsent(crate.getId(), k -> new HashMap<>());

        Hologram existing = map.get(blockPos);
        if (existing != null) {
            if (existing.getData() instanceof TextHologramData textData) {
                textData.setText(text);
            }
            return;
        }

        TextHologramData data = new TextHologramData(this.createName(crate, blockPos), location);
        data.setText(text);
        data.setBillboard(Config.CRATE_HOLOGRAM_BILLBOARD.get());
        data.setTextShadow(Config.CRATE_HOLOGRAM_SHADOW.get());
        data.setSeeThrough(Config.CRATE_HOLOGRAM_SEE_THROUGH.get());
        data.setBackground(this.createBackground());
        data.setVisibilityDistance(Config.CRATE_EFFECTS_VISIBILITY_DISTANCE.get());
        data.setPersistent(false);

        Hologram hologram = FancyHolograms.get().getHologramFactory().apply(data);
        this.getRegistry().register(hologram);

        map.put(blockPos, hologram);
    }

    @NotNull
    private List<String> createText(@NotNull Crate crate) {
        return Replacer.create().replace(crate.replacePlaceholders()).apply(crate.getHologramText()).stream()
            .map(NightMessage::asLegacy)
            .toList();
    }

    @NotNull
    private String createName(@NotNull Crate crate, @NotNull WorldPos blockPos) {
        return "excellentcrates_" + crate.getId().toLowerCase() + "_" + blockPos.getX() + "_" + blockPos.getY() + "_" + blockPos.getZ();
    }

    @Nullable
    private Color createBackground() {
        int[] bg = Config.CRATE_HOLOGRAM_BACKGROUND_COLOR.get();
        if (bg.length < 4) return null;

        return Color.fromARGB(bg[0], bg[1], bg[2], bg[3]);
    }
}
