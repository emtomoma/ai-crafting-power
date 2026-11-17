package com.emtomoma.aicraftingpower;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
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
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Mod("aicraftingpower")
public class AICraftingPowerMod {

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    public AICraftingPowerMod() {
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide() || !(player.level() instanceof ServerLevel serverLevel)) return;

        ItemStack result = event.getCrafting();
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(result.getItem());
        if (!itemId.getNamespace().contains("ai") && !result.has(DataComponents.CUSTOM_NAME)) return;

        CustomData existingData = result.get(DataComponents.CUSTOM_DATA);
        if (existingData != null && existingData.copyTag().contains("ai_func_id")) return;

        List<String> ingredients = new ArrayList<>();
        for (int i = 0; i < event.getInventory().getContainerSize(); i++) {
            ItemStack ing = event.getInventory().getItem(i);
            if (!ing.isEmpty()) {
                ingredients.add(BuiltInRegistries.ITEM.getKey(ing.getItem()).toString());
            }
        }

        String itemName = result.getHoverName().getString();
        String funcId = "item_" + UUID.randomUUID().toString().substring(0, 8);

        CompoundTag tag = existingData != null ? existingData.copyTag() : new CompoundTag();
        tag.putString("ai_func_id", funcId);
        result.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

        player.sendSystemMessage(Component.literal("[AI Power] Generating ability for: " + itemName + "...").withStyle(ChatFormatting.AQUA));

        CompletableFuture.runAsync(() -> callAI(serverLevel.getServer(), player, result, funcId, itemName, ingredients));
    }

    private void callAI(MinecraftServer server, Player player, ItemStack stack, String funcId, String itemName, List<String> ingredients) {
        try {
            String prompt = AIFunctionManager.SYSTEM_PROMPT_ADDON + "\n" +
                "Item name: '" + itemName + "', ingredients: " + ingredients + ". " +
                "Return ONLY pure JSON (write 'popis' in the same language as the item name): {\"popis\": \"short description of ability\", \"on_use_commands\": [...], \"on_hit_commands\": [...]}";

            String encoded = URLEncoder.encode(prompt, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://pollinations.ai" + encoded + "?json=true"))
                    .GET()
                    .build();

            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            String body = res.body().trim().replaceAll("^```[a-zA-Z]*\\n?", "").replaceAll("```$", "").trim();

            JsonObject json = GSON.fromJson(body, JsonObject.class);
            String popis = json.has("popis") ? json.get("popis").getAsString() : "Special AI Ability!";

            List<String> useCmds = new ArrayList<>();
            if (json.has("on_use_commands")) {
                JsonArray arr = json.getAsJsonArray("on_use_commands");
                arr.forEach(e -> useCmds.add(e.getAsString()));
            }

            List<String> hitCmds = new ArrayList<>();
            if (json.has("on_hit_commands")) {
                JsonArray arr = json.getAsJsonArray("on_hit_commands");
                arr.forEach(e -> hitCmds.add(e.getAsString()));
            }

            server.execute(() -> {
                AIFunctionManager.saveAndReload(server, funcId, useCmds, hitCmds);
                stack.set(DataComponents.LORE, new ItemLore(List.of(
                        Component.literal("⚡ " + popis).withStyle(ChatFormatting.GOLD),
                        Component.literal("Addon by emtomoma").withStyle(ChatFormatting.DARK_GRAY)
                )));
                player.sendSystemMessage(Component.literal("[AI Power] Ready: " + popis).withStyle(ChatFormatting.GREEN));
            });
        } catch (Exception e) {
            server.execute(() -> player.sendSystemMessage(Component.literal("[AI Power] AI Error.").withStyle(ChatFormatting.RED)));
        }
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
