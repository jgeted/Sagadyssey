package com.jgeted.sagadyssey.npc.appearance;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 数据化配色绑定；未知阵营显式回退中性，不按 ID 随机着色。 */
public record NpcOutfitCatalog(Set<String> palettes, Map<String, String> factions) {
    public NpcOutfitCatalog { palettes = Set.copyOf(palettes); factions = Map.copyOf(factions); }
    public String palette(String faction, String override) {
        if (override != null && palettes.contains(override)) return override;
        return factions.getOrDefault(faction == null ? "" : faction, "neutral");
    }
    public static NpcOutfitCatalog read(Reader reader) {
        JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
        Set<String> palettes = Set.copyOf(json.getAsJsonObject("palettes").keySet());
        if (!palettes.contains("neutral") || palettes.stream().anyMatch(key -> !key.matches("[a-z0-9_]{1,48}"))) {
            throw new IllegalArgumentException("Invalid outfit palettes");
        }
        Map<String, String> factions = new LinkedHashMap<>();
        json.getAsJsonObject("factions").entrySet().forEach(entry -> {
            String palette = entry.getValue().getAsString();
            if (!palettes.contains(palette)) throw new IllegalArgumentException("Unknown palette: " + palette);
            factions.put(entry.getKey(), palette);
        });
        return new NpcOutfitCatalog(palettes, factions);
    }
    public static NpcOutfitCatalog bundled() {
        try (var input = NpcOutfitCatalog.class.getResourceAsStream("/assets/sagadyssey/npc_outfits.json")) {
            if (input == null) throw new IllegalStateException("Missing outfit catalog");
            return read(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot read outfit catalog", e); }
    }
}
