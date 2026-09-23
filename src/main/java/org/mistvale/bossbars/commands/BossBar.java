package org.mistvale.bossbars.commands;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import org.cloudburstmc.protocol.bedrock.data.command.CommandParamType;
import org.cloudburstmc.protocol.bedrock.data.payload.boss.BossBarOverlay;
import org.cloudburstmc.protocol.bedrock.data.payload.boss.BossEventUpdateType;
import org.cloudburstmc.protocol.bedrock.packet.BossEventPacket;

import org.powernukkitx.Player;
import org.powernukkitx.Server;
import org.powernukkitx.command.Command;
import org.powernukkitx.command.CommandSender;
import org.powernukkitx.command.data.CommandEnum;
import org.powernukkitx.command.data.CommandParameter;
import org.powernukkitx.command.tree.ParamList;
import org.powernukkitx.command.utils.CommandLogger;
import org.powernukkitx.command.utils.RawText;
import org.powernukkitx.entity.Entity;
import org.powernukkitx.event.EventHandler;
import org.powernukkitx.event.Listener;
import org.powernukkitx.event.entity.EntityLevelChangeEvent;
import org.powernukkitx.event.player.PlayerJoinEvent;
import org.powernukkitx.event.player.PlayerQuitEvent;
import org.powernukkitx.plugin.PluginBase;
import org.powernukkitx.scheduler.TaskHandler;
import org.powernukkitx.utils.BossBarColor;
import org.powernukkitx.utils.DummyBossBar;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class BossBar extends Command implements Listener {

    private static final String[] COLORS = {"pink", "blue", "red", "green", "yellow", "purple", "white"};
    private static final String[] STYLES = {"progress", "notched_6", "notched_10", "notched_12", "notched_20"};

    private final PluginBase plugin;
    private final File saveFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, Bar> bars = new LinkedHashMap<>();
    private final Map<String, Map<UUID, Long>> shown = new HashMap<>();
    private final CommandEnum barIds = new CommandEnum("BossBarId", bars::keySet);
    private final TaskHandler liveRefresh;

    public BossBar(PluginBase plugin) {
        super("bossbar", "Creates and modifies bossbars.", "/bossbar <add|get|list|remove|set>");

        this.plugin = plugin;
        this.saveFile = new File(plugin.getDataFolder(), "bossbars.json");
        this.setPermission("mistvale.command.bossbar");

        commandParameters.clear();
        commandParameters.put("add", new CommandParameter[]{
                CommandParameter.newEnum("add", new String[]{"add"}),
                CommandParameter.newType("id", CommandParamType.ID),
                CommandParameter.newType("name", CommandParamType.MESSAGE)
        });

        commandParameters.put("get", new CommandParameter[]{
                CommandParameter.newEnum("get", new String[]{"get"}),
                CommandParameter.newEnum("id", barIds),
                CommandParameter.newEnum("property", new String[]{"max", "players", "value", "visible"})
        });

        commandParameters.put("list", new CommandParameter[]{
                CommandParameter.newEnum("list", new String[]{"list"})
        });

        commandParameters.put("remove", new CommandParameter[]{
                CommandParameter.newEnum("remove", new String[]{"remove"}),
                CommandParameter.newEnum("id", barIds)
        });

        commandParameters.put("set-color", setOverload("color", CommandParameter.newEnum("color", COLORS)));
        commandParameters.put("set-max", setOverload("max", CommandParameter.newType("max", CommandParamType.INT)));
        commandParameters.put("set-name", setOverload("name", CommandParameter.newType("name", CommandParamType.MESSAGE)));
        commandParameters.put("set-players", setOverload("players", CommandParameter.newType("targets", true, CommandParamType.SELECTION)));
        commandParameters.put("set-style", setOverload("style", CommandParameter.newEnum("style", STYLES)));
        commandParameters.put("set-value", setOverload("value", CommandParameter.newType("value", CommandParamType.INT)));
        commandParameters.put("set-visible", setOverload("visible", CommandParameter.newEnum("visible", CommandEnum.ENUM_BOOLEAN)));

        this.enableParamTree();

        load();

        this.liveRefresh = plugin.getServer().getScheduler().scheduleRepeatingTask(plugin, this::refreshLiveNames, 20);
    }

    private CommandParameter[] setOverload(String property, CommandParameter value) {
        return new CommandParameter[]{
                CommandParameter.newEnum("set", new String[]{"set"}),
                CommandParameter.newEnum("id", barIds),
                CommandParameter.newEnum(property, new String[]{property}),
                value
        };
    }

    @Override
    public int execute(CommandSender sender, String commandLabel, Map.Entry<String, ParamList> result, CommandLogger log) {
        ParamList list = result.getValue();
        String overload = result.getKey();

        if (overload.equals("list")) {
            if (bars.isEmpty()) {
                log.addSuccess("§fThere are no custom bossbars active").output();
            } else {
                String names = bars.values().stream().map(b -> b.display(sender)).collect(Collectors.joining(", "));
                log.addSuccess("§fThere are " + bars.size() + " custom bossbar(s) active: " + names).output();
            }
            return bars.size();
        }

        String id = normalizeId(list.getResult(1));

        if (overload.equals("add")) {
            if (bars.containsKey(id)) {
                log.addError("A bossbar already exists with the ID '" + id + "'").output();
                return 0;
            }
            String name = list.getResult(2);
            String invalid = validateRawText(name);
            if (invalid != null) {
                log.addError(invalid).output();
                return 0;
            }
            Bar bar = new Bar();
            bar.id = id;
            bar.name = name;
            bars.put(id, bar);
            barIds.updateSoftEnum();
            save();
            log.addSuccess("§fCreated custom bossbar " + bar.display(sender)).output();
            return bars.size();
        }

        Bar bar = bars.get(id);
        if (bar == null) {
            log.addError("No bossbar exists with the ID '" + id + "'").output();
            return 0;
        }

        switch (overload) {
            case "remove" -> {
                hideAll(bar);
                bars.remove(id);
                shown.remove(id);
                barIds.updateSoftEnum();
                save();
                log.addSuccess("§fRemoved custom bossbar " + bar.display(sender)).output();
                return bars.size();
            }
            case "get" -> {
                String property = list.getResult(2);
                switch (property) {
                    case "max" -> {
                        log.addSuccess("§fCustom bossbar " + bar.display(sender) + " has a maximum of " + bar.max).output();
                        return bar.max;
                    }
                    case "value" -> {
                        log.addSuccess("§fCustom bossbar " + bar.display(sender) + " has a value of " + bar.value).output();
                        return bar.value;
                    }
                    case "visible" -> {
                        log.addSuccess("§fCustom bossbar " + bar.display(sender) + " is currently " + (bar.visible ? "shown" : "hidden")).output();
                        return bar.visible ? 1 : 0;
                    }
                    default -> {
                        List<String> online = onlinePlayers(bar).stream().map(Player::getName).toList();
                        if (online.isEmpty()) {
                            log.addSuccess("§fCustom bossbar " + bar.display(sender) + " has no players currently online").output();
                        } else {
                            log.addSuccess("§fCustom bossbar " + bar.display(sender) + " has " + online.size()
                                    + " player(s) currently online: " + String.join(", ", online)).output();
                        }
                        return online.size();
                    }
                }
            }
        }

        String property = overload.substring("set-".length());
        switch (property) {
            case "color" -> {
                String color = list.getResult(3);
                if (color.equals(bar.color)) {
                    log.addError("Nothing changed. That's already the color of this bossbar").output();
                    return 0;
                }
                bar.color = color;
                refresh(bar);
                log.addSuccess("§fCustom bossbar " + bar.display(sender) + " has changed color").output();
            }
            case "max" -> {
                int max = list.getResult(3);
                if (max < 1) {
                    log.addError(tooSmall(list.getResult(3), 1)).output();
                    return 0;
                }
                if (max == bar.max) {
                    log.addError("Nothing changed. That's already the max of this bossbar").output();
                    return 0;
                }
                bar.max = max;
                refresh(bar);
                log.addSuccess("§fCustom bossbar " + bar.display(sender) + " has changed maximum to " + max).output();
                return max;
            }
            case "name" -> {
                String name = list.getResult(3);
                String invalid = validateRawText(name);
                if (invalid != null) {
                    log.addError(invalid).output();
                    return 0;
                }
                if (name.equals(bar.name)) {
                    log.addError("Nothing changed. That's already the name of this bossbar").output();
                    return 0;
                }
                bar.name = name;
                refresh(bar);
                log.addSuccess("§fCustom bossbar " + bar.display(sender) + " has been renamed").output();
            }
            case "players" -> {
                Set<UUID> next = new LinkedHashSet<>();
                Map<UUID, String> nextNames = new HashMap<>();
                if (list.hasResult(3)) {
                    List<Entity> targets = list.getResult(3);
                    for (Entity entity : targets) {
                        if (entity instanceof Player player) {
                            next.add(player.getUniqueId());
                            nextNames.put(player.getUniqueId(), player.getName());
                        }
                    }
                }
                if (next.equals(bar.players)) {
                    log.addError("Nothing changed. Those players are already on the bossbar with nobody to add or remove").output();
                    return 0;
                }
                hideAll(bar);
                bar.players = next;
                showAll(bar);
                save();
                if (next.isEmpty()) {
                    log.addSuccess("§fCustom bossbar " + bar.display(sender) + " no longer has any players").output();
                } else {
                    log.addSuccess("§fCustom bossbar " + bar.display(sender) + " now has " + next.size()
                            + " player(s): " + String.join(", ", nextNames.values())).output();
                }
                return next.size();
            }
            case "style" -> {
                String style = list.getResult(3);
                if (style.equals(bar.style)) {
                    log.addError("Nothing changed. That's already the style of this bossbar").output();
                    return 0;
                }
                bar.style = style;
                refresh(bar);
                log.addSuccess("§fCustom bossbar " + bar.display(sender) + " has changed style").output();
            }
            case "value" -> {
                int value = list.getResult(3);
                if (value < 0) {
                    log.addError(tooSmall(list.getResult(3), 0)).output();
                    return 0;
                }
                if (value == bar.value) {
                    log.addError("Nothing changed. That's already the value of this bossbar").output();
                    return 0;
                }
                bar.value = value;
                refresh(bar);
                log.addSuccess("§fCustom bossbar " + bar.display(sender) + " has changed value to " + value).output();
                return value;
            }
            case "visible" -> {
                boolean visible = Boolean.parseBoolean(String.valueOf((Object) list.getResult(3)));
                if (visible == bar.visible) {
                    log.addError("Nothing changed. The bossbar is already " + (visible ? "visible" : "hidden")).output();
                    return 0;
                }
                if (visible) {
                    bar.visible = true;
                    showAll(bar);
                } else {
                    hideAll(bar);
                    bar.visible = false;
                }
                save();
                log.addSuccess("§fCustom bossbar " + bar.display(sender) + " is now " + (visible ? "visible" : "hidden")).output();
            }
        }
        return 1;
    }

    private void show(Bar bar, Player player) {
        if (!bar.visible || !player.isOnline()) return;
        Map<UUID, Long> perPlayer = shown.computeIfAbsent(bar.id, k -> new HashMap<>());
        if (perPlayer.containsKey(player.getUniqueId())) return;

        DummyBossBar dummy = new DummyBossBar.Builder(player)
                .text(bar.label(player))
                .length(bar.percent())
                .color(bar.bossBarColor())
                .build();
        long dummyId = player.createBossBar(dummy);
        perPlayer.put(player.getUniqueId(), dummyId);
        sendState(bar, player, dummyId);
    }

    private void hide(Bar bar, Player player) {
        Map<UUID, Long> perPlayer = shown.get(bar.id);
        if (perPlayer == null) return;
        Long dummyId = perPlayer.remove(player.getUniqueId());
        if (dummyId != null && player.isOnline()) {
            player.removeBossBar(dummyId);
        }
    }

    private void showAll(Bar bar) {
        onlinePlayers(bar).forEach(p -> show(bar, p));
    }

    private void hideAll(Bar bar) {
        onlinePlayers(bar).forEach(p -> hide(bar, p));
    }

    private void refresh(Bar bar) {
        save();
        Map<UUID, Long> perPlayer = shown.get(bar.id);
        if (perPlayer == null) return;
        for (Player player : onlinePlayers(bar)) {
            Long dummyId = perPlayer.get(player.getUniqueId());
            if (dummyId == null) continue;
            DummyBossBar dummy = player.getDummyBossBar(dummyId);
            if (dummy == null) continue;
            String rendered = bar.label(player);
            if (!dummy.getText().equals(rendered)) dummy.setText(rendered);
            if (dummy.getLength() != bar.percent()) dummy.setLength(bar.percent());
            if (dummy.getColor() != bar.bossBarColor()) dummy.setColor(bar.bossBarColor());
            sendState(bar, player, dummyId);
        }
    }

    private void refreshLiveNames() {
        for (Bar bar : bars.values()) {
            if (!bar.visible || !isRawText(bar.name)) continue;
            Map<UUID, Long> perPlayer = shown.get(bar.id);
            if (perPlayer == null) continue;
            for (Player player : onlinePlayers(bar)) {
                Long dummyId = perPlayer.get(player.getUniqueId());
                if (dummyId == null) continue;
                DummyBossBar dummy = player.getDummyBossBar(dummyId);
                if (dummy == null) continue;
                String rendered = bar.label(player);
                if (!dummy.getText().equals(rendered)) dummy.setText(rendered);
            }
        }
    }

    private void sendState(Bar bar, Player player, long dummyId) {
        BossEventPacket percent = new BossEventPacket();
        percent.setTargetActorID(dummyId);
        percent.setEventType(BossEventUpdateType.UPDATE_PERCENT);
        percent.setHealthPercent(bar.progress());

        BossEventPacket style = new BossEventPacket();
        style.setTargetActorID(dummyId);
        style.setEventType(BossEventUpdateType.UPDATE_STYLE);
        String rendered = bar.label(player);
        style.setName(rendered);
        style.setFilteredName(rendered);
        style.setHealthPercent(bar.progress());
        style.setColor(bar.bossBarColor().toNetwork());
        style.setOverlay(BossBarOverlay.valueOf(bar.style.toUpperCase(Locale.ROOT)));

        player.sendPackets(style, percent);
    }

    private List<Player> onlinePlayers(Bar bar) {
        List<Player> result = new ArrayList<>();
        for (UUID uuid : bar.players) {
            Player player = plugin.getServer().getOnlinePlayers().get(uuid);
            if (player != null && player.isOnline()) result.add(player);
        }
        return result;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        for (Bar bar : bars.values()) {
            if (bar.players.contains(player.getUniqueId())) show(bar, player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        shown.values().forEach(perPlayer -> perPlayer.remove(uuid));
    }

    @EventHandler
    public void onLevelChange(EntityLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        reshowWhenLoaded(player, 0);
    }

    private void reshowWhenLoaded(Player player, int waited) {
        plugin.getServer().getScheduler().scheduleDelayedTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (awaitingDimensionChange(player) && waited < 200) {
                reshowWhenLoaded(player, waited + 5);
                return;
            }
            plugin.getServer().getScheduler().scheduleDelayedTask(plugin, () -> {
                if (!player.isOnline()) return;
                for (Bar bar : bars.values()) {
                    if (!bar.players.contains(player.getUniqueId())) continue;
                    hide(bar, player);
                    show(bar, player);
                }
            }, 10);
        }, 5);
    }

    private static boolean awaitingDimensionChange(Player player) {
        try {
            Field field = Player.class.getDeclaredField("needDimensionChangeACK");
            field.setAccessible(true);
            return field.getBoolean(player);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    public void shutdown() {
        liveRefresh.cancel();
        save();
        bars.values().forEach(this::hideAll);
        shown.clear();
    }

    private void load() {
        if (!saveFile.exists()) return;
        try (Reader reader = Files.newBufferedReader(saveFile.toPath(), StandardCharsets.UTF_8)) {
            List<Bar> loaded = gson.fromJson(reader, new TypeToken<List<Bar>>() {}.getType());
            if (loaded != null) loaded.forEach(bar -> bars.put(bar.id, bar));
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().error("Failed to load bossbars.json", e);
        }
    }

    private void save() {
        try {
            saveFile.getParentFile().mkdirs();
            try (Writer writer = Files.newBufferedWriter(saveFile.toPath(), StandardCharsets.UTF_8)) {
                gson.toJson(new ArrayList<>(bars.values()), writer);
            }
        } catch (IOException e) {
            plugin.getLogger().error("Failed to save bossbars.json", e);
        }
    }

    private static String normalizeId(String id) {
        id = id.toLowerCase(Locale.ROOT);
        return id.contains(":") ? id : "minecraft:" + id;
    }

    private static class Bar {
        String id;
        String name;
        String color = "white";
        String style = "progress";
        int value = 0;
        int max = 100;
        boolean visible = true;
        Set<UUID> players = new LinkedHashSet<>();

        float percent() {
            return Math.max(0.01f, progress() * 100f);
        }

        float progress() {
            return Math.clamp((float) value / max, 0f, 1f);
        }

        BossBarColor bossBarColor() {
            return BossBarColor.valueOf(color.toUpperCase(Locale.ROOT));
        }

        String render(CommandSender viewer) {
            if (!isRawText(name)) return name;
            try {
                JsonElement parts = JsonParser.parseString(name).getAsJsonObject().get("rawtext");
                if (parts == null || !parts.isJsonArray()) return renderPart(name, viewer);
                StringBuilder builder = new StringBuilder();
                for (JsonElement part : parts.getAsJsonArray()) {
                    JsonObject single = new JsonObject();
                    JsonArray array = new JsonArray();
                    array.add(part);
                    single.add("rawtext", array);
                    builder.append(renderPart(single.toString(), viewer));
                }
                return builder.toString();
            } catch (RuntimeException e) {
                return "";
            }
        }

        private static String renderPart(String json, CommandSender viewer) {
            try {
                RawText rawText = RawText.fromRawText(json);
                rawText.preParse(viewer);
                return flatten(rawText.getBase());
            } catch (RuntimeException e) {
                return "";
            }
        }

        String label(CommandSender viewer) {
            return switch (style) {
                case "notched_6" -> "§n§6";
                case "notched_10" -> "§n§1§0";
                case "notched_12" -> "§n§1§2";
                case "notched_20" -> "§n§2§0";
                default -> "";
            } + "§p§" + Arrays.asList(COLORS).indexOf(color) + render(viewer);
        }

        String display(CommandSender viewer) {
            return "[" + render(viewer) + "§r§f]";
        }
    }

    private static boolean isRawText(String text) {
        return text.stripLeading().startsWith("{");
    }

    private static String validateRawText(String text) {
        if (!isRawText(text)) return null;
        int end = jsonEnd(text);
        if (end < 0) return "Invalid rawtext JSON: missing closing '}'";
        String trailing = text.substring(end).strip();
        if (!trailing.isEmpty()) return "Invalid rawtext JSON: unexpected text after JSON: '" + trailing + "'";
        try {
            RawText rawText = RawText.fromRawText(text);
            if (rawText == null || rawText.getBase() == null) return "Invalid rawtext JSON";
            return null;
        } catch (RuntimeException e) {
            Matcher column = Pattern.compile("column (\\d+)").matcher(String.valueOf(e.getMessage()));
            return column.find() ? "Invalid rawtext JSON at column " + column.group(1) : "Invalid rawtext JSON";
        }
    }

    private static int jsonEnd(String text) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                if (--depth == 0) return i + 1;
            }
        }
        return -1;
    }

    private static String flatten(RawText.Component component) {
        if (component == null) return "";
        return switch (component.getType()) {
            case TEXT -> Objects.toString(component.getComponent_text(), "");
            case RAWTEXT -> component.getComponent_rawtext().stream().map(BossBar::flatten).collect(Collectors.joining());
            case TRANSLATE, TRANSLATE_WITH -> translate(component.getComponent_translate(), component.getComponent_translate_with());
            default -> Objects.toString(component.getComponent_text(), "");
        };
    }

    private static String translate(String key, Object with) {
        List<String> args = new ArrayList<>();
        if (with instanceof Collection<?> collection) {
            collection.forEach(arg -> args.add(flattenJson(arg)));
        } else if (with instanceof Map<?, ?> map && map.get("rawtext") instanceof Collection<?> rawtext) {
            rawtext.forEach(arg -> args.add(flattenJson(arg)));
        } else if (with != null) {
            args.add(flattenJson(with));
        }
        return Server.getInstance().getLanguage().tr(key, args.toArray(new String[0]));
    }

    private static String flattenJson(Object value) {
        if (value instanceof RawText.Component component) return flatten(component);
        if (value instanceof Map<?, ?> map) {
            if (map.get("text") != null) return String.valueOf(map.get("text"));
            if (map.get("translate") != null) return translate(String.valueOf(map.get("translate")), map.get("with"));
            if (map.get("rawtext") instanceof Collection<?> rawtext) {
                StringBuilder builder = new StringBuilder();
                rawtext.forEach(part -> builder.append(flattenJson(part)));
                return builder.toString();
            }
        }
        return String.valueOf(value);
    }

    private static String tooSmall(int number, int min) {
        return "The number you have entered (" + number + ") is too small, it must be at least " + min;
    }
}