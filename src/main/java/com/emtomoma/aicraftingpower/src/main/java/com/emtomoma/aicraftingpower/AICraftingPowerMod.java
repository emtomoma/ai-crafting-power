package com.emtomoma.aicraftingpower;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Mod("aicraftingpower")
public class AICraftingPowerMod {

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public AICraftingPowerMod() {
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide() || !(player.level() instanceof ServerLevel serverLevel)) return;
        if (player.tickCount % 20 != 0) return;

        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) return;

        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!itemId.getNamespace().contains("ai") && !itemId.getPath().contains("ai_")) return;

        CustomData existingData = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = existingData != null ? existingData.copyTag() : new CompoundTag();
        if (tag.contains("ai_func_id") || tag.getBoolean("ai_generating")) return;

        tag.putBoolean("ai_generating", true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

        String itemName = stack.getHoverName().getString();
        String loreText = stack.has(DataComponents.LORE) ? stack.get(DataComponents.LORE).lines().toString() : "";
        String funcId = "item_" + UUID.randomUUID().toString().substring(0, 8);

        player.sendSystemMessage(Component.literal("[AI Power] Vymyslim schopnost pro: " + itemName + "...").withStyle(ChatFormatting.AQUA));

        CompletableFuture.runAsync(() -> callAI(serverLevel.getServer(), player, stack, funcId, itemName, loreText));
    }

    private void callAI(MinecraftServer server, Player player, ItemStack stack, String funcId, String itemName, String loreText) {
        List<String> useCmds = new ArrayList<>();
        List<String> hitCmds = new ArrayList<>();
        String popis = "Specialni AI schopnost!";

        try {
            String prompt = AIFunctionManager.SYSTEM_PROMPT_ADDON + "\n" +
                "Item name: '" + itemName + "', lore/details: '" + loreText + "'. " +
                "Return ONLY pure JSON (write 'popis' in Czech or item language): {\"popis\": \"kratky popis schopnosti\", \"on_use_commands\": [...], \"on_hit_commands\": [...]}";

            String encoded = URLEncoder.encode(prompt, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://pollinations.ai" + encoded + "?json=true"))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            String body = res.body().trim().replaceAll("^```[a-zA-Z]*\\n?", "").replaceAll("```$", "").trim();

            JsonObject json = GSON.fromJson(body, JsonObject.class);
            if (json.has("popis")) popis = json.get("popis").getAsString();
            if (json.has("on_use_commands")) {
                for (JsonElement e : json.getAsJsonArray("on_use_commands")) {
                    useCmds.add(e.getAsString());
                }
            }
            if (json.has("on_hit_commands")) {
                for (JsonElement e : json.getAsJsonArray("on_hit_commands")) {
                    hitCmds.add(e.getAsString());
                }
            }
        } catch (Exception ignored) {
            String lower = (itemName + " " + loreText).toLowerCase();
            if (lower.contains("nether") || lower.contains("star") || lower.contains("hvězd") || lower.contains("wither")) {
                popis = "Hvězdná bouře (blesky a síla)";
                useCmds.add("effect give @s fire_resistance 4 1 true");
                useCmds.add("effect give @s resistance 3 4 true");
                useCmds.add("effect give @s strength 10 2 true");
                useCmds.add("execute anchored eyes positioned ^ ^ ^12 run summon lightning_bolt ~ ~ ~");
                hitCmds.add("effect give @s fire_resistance 3 1 true");
                hitCmds.add("execute at @s run summon lightning_bolt ~ ~ ~");
            } else if (lower.contains("tnt") || lower.contains("boom") || lower.contains("výbu") || lower.contains("exploz")) {
                popis = "Výbušný úder TNT";
                useCmds.add("effect give @s resistance 2 4 true");
                useCmds.add("execute anchored eyes positioned ^ ^ ^6 run summon tnt ~ ~ ~ {fuse:10}");
                hitCmds.add("effect give @s resistance 2 4 true");
                hitCmds.add("execute at @s run summon tnt ~ ~ ~ {fuse:0}");
            } else if (lower.contains("ender") || lower.contains("pearl") || lower.contains("teleport")) {
                popis = "Ender teleportace";
                useCmds.add("execute at @s positioned ^ ^ ^12 run tp @s ~ ~ ~");
                useCmds.add("execute at @s run playsound minecraft:entity.enderman.teleport master @a ~ ~ ~");
                hitCmds.add("execute at @s run tp @s ~ ~6 ~");
            } else {
                popis = "Bleskový výboj";
                useCmds.add("effect give @s fire_resistance 3 1 true");
                useCmds.add("execute anchored eyes positioned ^ ^ ^10 run summon lightning_bolt ~ ~ ~");
                hitCmds.add("effect give @s fire_resistance 3 1 true");
                hitCmds.add("execute at @s run summon lightning_bolt ~ ~ ~");
            }
        }

        final List<String> finalUse = new ArrayList<>(useCmds);
        final List<String> finalHit = new ArrayList<>(hitCmds);
        final String finalPopis = popis;

        server.execute(() -> {
            AIFunctionManager.saveAndReload(server, funcId, finalUse, finalHit);
            CustomData cur = stack.get(DataComponents.CUSTOM_DATA);
            CompoundTag tag = cur != null ? cur.copyTag() : new CompoundTag();
            tag.remove("ai_generating");
            tag.putString("ai_func_id", funcId);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

            stack.set(DataComponents.LORE, new ItemLore(List.of(
                    Component.literal("⚡ " + finalPopis).withStyle(ChatFormatting.GOLD),
                    Component.literal("Addon by emtomoma").withStyle(ChatFormatting.DARK_GRAY)
            )));
            player.sendSystemMessage(Component.literal("[AI Power] Schopnost pripravena: " + finalPopis).withStyle(ChatFormatting.GREEN));
        });
    }

    @SubscribeEvent
    public void onRightClick(PlayerInteractEvent.RightClickItem event) {
        Player player = event.getEntity();
        if (player.level().isClientSide() || !(player.level() instanceof ServerLevel level)) return;

        ItemStack stack = event.getItemStack();
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null && data.copyTag().contains("ai_func_id")) {
            AIFunctionManager.executeUse(level, player, data.copyTag().getString("ai_func_id"));
            player.getCooldowns().addCooldown(stack.getItem(), 20);
        }
    }

    @SubscribeEvent
    public void onHitEntity(LivingDamageEvent.Pre event) {
        if (!(event.getSource().getEntity() instanceof Player attacker)) return;
        if (attacker.level().isClientSide() || !(attacker.level() instanceof ServerLevel level)) return;

        ItemStack stack = attacker.getMainHandItem();
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null && data.copyTag().contains("ai_func_id")) {
            AIFunctionManager.executeHit(level, event.getEntity(), attacker, data.copyTag().getString("ai_func_id"));
        }
    }
}
