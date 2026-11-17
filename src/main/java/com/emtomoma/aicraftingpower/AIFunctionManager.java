package com.emtomoma.aicraftingpower;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class AIFunctionManager {

    public static final String SYSTEM_PROMPT_ADDON = """
        Podle vložených surovin (ingredients) a názvu itemu vymysli unikátní schopnost a vygeneruj pole 'on_use_commands' (při kliknutí pravým tlačítkem) a 'on_hit_commands' (při úderu do nepřítele).
        
        POVINNÁ PRAVIDLA PRO PŘÍKAZY (Minecraft 1.21.1, bez lomítka na začátku):
        1. VŽDY používej příkaz 'execute' pro přesné cílení!
        2. V 'on_use_commands' (kliknutí) VŽDY použij 'execute anchored eyes positioned ^ ^ ^10 run ...' (nebo ^ ^ ^6 pro TNT, ^ ^ ^12 pro teleport), aby efekt dopadl před hráče tam, kam se dívá, NIKDY ne přímo na hráče!
        3. V 'on_hit_commands' (úder) se příkaz spouští na pozici nepřítele – použij 'execute at @s run ...' (např. 'execute at @s run summon lightning_bolt ~ ~ ~').
        4. VŽDY přidej ochranu pro hráče '@s' ('effect give @s fire_resistance 4 1 true' a 'effect give @s resistance 2 4 true'), pokud funkce vyvolává blesk, oheň nebo výbuch!
        5. NIKDY nepoužívej zakázané příkazy jako 'stop', 'ban', 'kick', 'op', 'deop' nebo 'kill @a'.
        6. Vymýšlej kreativní kombinace pro JAKÉKOLIV suroviny (diamant, led, peří, ender perla, nether star, redstone, střelný prach atd.).

        Příklady formátu:
        - Nether Star ('minecraft:nether_star'):
          on_use_commands: ["effect give @s fire_resistance 4 1 true", "effect give @s resistance 3 4 true", "effect give @s strength 10 2 true", "execute anchored eyes positioned ^ ^ ^12 run summon lightning_bolt ~ ~ ~"]
          on_hit_commands: ["effect give @s fire_resistance 3 1 true", "execute at @s run summon lightning_bolt ~ ~ ~"]
        - Hromosvod / Měď ('minecraft:lightning_rod', 'minecraft:copper_ingot'):
          on_use_commands: ["effect give @s fire_resistance 3 1 true", "execute anchored eyes positioned ^ ^ ^10 run summon lightning_bolt ~ ~ ~"]
          on_hit_commands: ["execute at @s run summon lightning_bolt ~ ~ ~"]
        - TNT ('minecraft:tnt'):
          on_use_commands: ["effect give @s resistance 2 4 true", "execute anchored eyes positioned ^ ^ ^6 run summon tnt ~ ~ ~ {fuse:10}"]
          on_hit_commands: ["effect give @s resistance 2 4 true", "execute at @s run summon tnt ~ ~ ~ {fuse:0}"]
        - Ender perla ('minecraft:ender_pearl'):
          on_use_commands: ["execute at @s positioned ^ ^ ^12 run tp @s ~ ~ ~", "execute at @s run playsound minecraft:entity.enderman.teleport master @a ~ ~ ~"]
          on_hit_commands: ["execute at @s run tp @s ~ ~6 ~"]
        """;

    public static void saveAndReload(MinecraftServer server, String itemId, List<String> useCmds, List<String> hitCmds) {
        try {
            Path packDir = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve("ai_generated_pack");
            Path funcDir = packDir.resolve("data/aicraft/function");
            Files.createDirectories(funcDir);

            Path mcmeta = packDir.resolve("pack.mcmeta");
            if (!Files.exists(mcmeta)) {
                Files.writeString(mcmeta, "{\"pack\":{\"pack_format\":48,\"description\":\"AI Generated Item Functions by emtomoma\"}}");
            }

            if (useCmds != null && !useCmds.isEmpty()) {
                Files.write(funcDir.resolve(itemId + "_use.mcfunction"), filterSafeCommands(useCmds));
            }
            if (hitCmds != null && !hitCmds.isEmpty()) {
                Files.write(funcDir.resolve(itemId + "_hit.mcfunction"), filterSafeCommands(hitCmds));
            }

            server.getPackRepository().reload();
            List<String> selected = new ArrayList<>(server.getPackRepository().getSelectedIds());
            if (!selected.contains("file/ai_generated_pack")) {
                selected.add("file/ai_generated_pack");
            }
            server.reloadResources(selected);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static List<String> filterSafeCommands(List<String> cmds) {
        List<String> safe = new ArrayList<>();
        for (String cmd : cmds) {
            String lower = cmd.toLowerCase().trim();
            if (!lower.startsWith("stop") && !lower.startsWith("ban") && !lower.startsWith("op") && !lower.contains("kill @a") && !lower.contains("kill @e")) {
                safe.add(cmd);
            }
        }
        return safe;
    }

    public static void executeUse(ServerLevel level, Player player, String itemId) {
        MinecraftServer server = level.getServer();
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("aicraft", itemId + "_use");
        server.getFunctions().get(id).ifPresent(func -> {
            CommandSourceStack source = player.createCommandSourceStack().withPermission(2).withSuppressedOutput();
            server.getFunctions().execute(func, source);
        });
    }

    public static void executeHit(ServerLevel level, LivingEntity target, LivingEntity attacker, String itemId) {
        MinecraftServer server = level.getServer();
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("aicraft", itemId + "_hit");
        server.getFunctions().get(id).ifPresent(func -> {
            CommandSourceStack source = attacker.createCommandSourceStack()
                    .withPosition(target.position())
                    .withPermission(2)
                    .withSuppressedOutput();
            server.getFunctions().execute(func, source);
        });
    }
}
