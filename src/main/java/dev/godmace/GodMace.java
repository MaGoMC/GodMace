package dev.godmace;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.ClipContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Source port: does not use Bukkit. Must be compiled and tested on Minecraft 26.2. */
public final class GodMace implements ModInitializer {
    private static final String FLAG = "godmace";
    private static final String KILLS = "godmace_kills";
    private static final Map<UUID, Long> NEXT_STAB = new HashMap<>();
    private static final Map<UUID, Long> NEXT_STRIKE = new HashMap<>();
    private static final Map<UUID, Integer> NUKE_CHARGES = new HashMap<>();
    private static final Map<UUID, Integer> STAB_CHARGES = new HashMap<>();
    private static long ticks;
    private static final java.util.List<StabWave> ACTIVE_STABS = new ArrayList<>();

    private static final class StabWave {
        final ServerLevel level;
        final UUID owner;
        final double x, z;
        final int bottom;
        int y;
        long nextTick;

        StabWave(ServerPlayer player, Vec3 target) {
            this.level = player.level();
            this.owner = player.getUUID();
            this.x = Math.floor(target.x) + 0.5;
            this.z = Math.floor(target.z) + 0.5;
            this.y = Math.min(level.getMaxY() - 12, (int)Math.floor(target.y) + 55);
            this.bottom = Math.max(level.getMinY() + 4, (int)Math.floor(target.y) - 24);
            this.nextTick = ticks;
        }
    }

    private static void tickStabs(net.minecraft.server.MinecraftServer server) {
        var iterator = ACTIVE_STABS.iterator();
        while (iterator.hasNext()) {
            StabWave wave = iterator.next();
            if (wave.y < wave.bottom) {
                iterator.remove();
                continue;
            }
            if (ticks < wave.nextTick) continue;
            ServerPlayer owner = server.getPlayerList().getPlayer(wave.owner);
            if (owner == null || owner.level() != wave.level) {
                iterator.remove();
                continue;
            }
            // Small staggered TNT bursts rather than hundreds of instant explosions.
            for (int i = 0; i < 2; i++) {
                double offset = i == 0 ? -0.55 : 0.55;
                PrimedTnt tnt = new PrimedTnt(wave.level, wave.x + offset, wave.y, wave.z, owner);
                tnt.setFuse(22);
                wave.level.addFreshEntity(tnt);
            }
            wave.y -= 4;
            wave.nextTick = ticks + 4;
        }
    }

    @Override public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            dispatcher.register(Commands.literal("getmace")
                .requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    player.getInventory().add(makeMace(0));
                    ctx.getSource().sendSuccess(() -> Component.literal("GOD MACE added to inventory"), false);
                    return 1;
                }));
            dispatcher.register(Commands.literal("setmacekills")
                .requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.argument("kills", IntegerArgumentType.integer(0))
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        ItemStack held = player.getMainHandItem();
                        if (!isMace(held)) { ctx.getSource().sendFailure(Component.literal("Hold GOD MACE first")); return 0; }
                        setKills(held, IntegerArgumentType.getInteger(ctx, "kills"));
                        return 1;
                    })));
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ticks++;
            tickStabs(server);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (ticks % 40 == 0) applyPassiveEffects(player);

            }
        });
        // Count direct kills when the victim dies, not merely when the mace hits.
        ServerLivingEntityEvents.AFTER_DEATH.register((victim, damageSource) -> {
            // Only real player-vs-player kills count.
            if (!(victim instanceof ServerPlayer)) return;
            Entity attacker = damageSource.getEntity();
            if (!(attacker instanceof ServerPlayer killer) || attacker == victim) return;
            ItemStack mace = killer.getMainHandItem();
            if (!isMace(mace)) return;
            int newKills = getKills(mace) + 1;
            setKills(mace, newKills);
            UUID id = killer.getUUID();
            NUKE_CHARGES.merge(id, 2, (oldValue, bonus) -> Math.min(1000000, oldValue + bonus));
            STAB_CHARGES.merge(id, 2, (oldValue, bonus) -> Math.min(1000000, oldValue + bonus));
            upgradeEnchantments(killer, mace, newKills);
            killer.sendSystemMessage(Component.literal("GOD MACE PvP kills: " + newKills
                + " | Nukes: " + NUKE_CHARGES.get(id)
                + " | Stabs: " + STAB_CHARGES.get(id)).withStyle(ChatFormatting.GOLD));
        });
        AttackEntityCallback.EVENT.register((player, level, hand, target, hit) -> {
            if (level.isClientSide() || !isMace(player.getItemInHand(hand))) return InteractionResult.PASS;
            if (target instanceof LivingEntity living) {
                int kills = getKills(player.getItemInHand(hand));
                living.hurtServer((ServerLevel) level, level.damageSources().playerAttack(player), 2.0f + Math.min(60, kills * 2));
                living.igniteForSeconds(Math.min(10, 2 + kills));
            }
            return InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (!isMace(stack)) return InteractionResult.PASS;
            if (level.isClientSide()) return InteractionResult.SUCCESS;
            if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
            if (player.isShiftKeyDown()) stab(serverPlayer, getKills(stack));
            else strike(serverPlayer, getKills(stack));
            return InteractionResult.SUCCESS;
        });
    }

    public static ItemStack makeMace(int kills) {
        ItemStack stack = new ItemStack(Items.MACE);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("GOD MACE").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
        setKills(stack, kills);
        return stack;
    }
    public static boolean isMace(ItemStack stack) {
        if (!stack.is(Items.MACE)) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBoolean(FLAG).orElse(false);
    }
    public static int getKills(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? 0 : data.copyTag().getInt(KILLS).orElse(0);
    }
    public static void setKills(ItemStack stack, int kills) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putBoolean(FLAG, true);
            tag.putInt(KILLS, Math.max(0, kills));
        });
        stack.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(
            java.util.List.of(Component.literal("Kills: " + Math.max(0, kills)).withStyle(ChatFormatting.DARK_GRAY))));
    }
    // Rotate mace-compatible upgrades. Density and Breach conflict, so use Density.
    // Standard maximum enchantment levels are respected.
    private static void upgradeEnchantments(ServerPlayer player, ItemStack mace, int kills) {
        var enchantments = player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        int stage = (kills - 1) % 4;
        if (stage == 0) mace.enchant(enchantments.getOrThrow(Enchantments.DENSITY), Math.min(5, (kills + 3) / 4));
        else if (stage == 1) mace.enchant(enchantments.getOrThrow(Enchantments.UNBREAKING), Math.min(3, (kills + 2) / 4));
        else if (stage == 2) mace.enchant(enchantments.getOrThrow(Enchantments.WIND_BURST), Math.min(3, (kills + 1) / 4));
        else mace.enchant(enchantments.getOrThrow(Enchantments.MENDING), 1);
    }

    private static void applyPassiveEffects(ServerPlayer player) {
        int maxKills = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (isMace(stack)) maxKills = Math.max(maxKills, getKills(stack));
        }
        if (isMace(player.getMainHandItem())) maxKills = Math.max(maxKills, getKills(player.getMainHandItem()));
        if (maxKills == 0) return;
        if (maxKills >= 3) player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100, 0, true, false));
        if (maxKills >= 5) player.addEffect(new MobEffectInstance(MobEffects.SPEED, 100, 0, true, false));
        if (maxKills >= 7) player.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 100, 0, true, false));
        if (maxKills >= 10) player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 0, true, false));
    }
    // Calls the installed OrbitalStrike+ mod without bundling or copying its code.
    // Keep orbitalstrike+26.2-1.0.18.jar installed alongside GodMace.
    private static boolean callOrbital(String method, ServerLevel level, int... args) {
        try {
            Class<?> logic = Class.forName("com.mcclip.orbitalstrike.OrbitalStrikeLogic");
            if ("summonNuke".equals(method)) {
                logic.getMethod(method, ServerLevel.class, int.class, int.class, int.class, int.class)
                    .invoke(null, level, args[0], args[1], args[2], args[3]);
            } else {
                logic.getMethod(method, ServerLevel.class, int.class, int.class)
                    .invoke(null, level, args[0], args[1], args[2]);
            }
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            System.err.println("[GodMace] OrbitalStrike+ integration failed: " + e);
            return false;
        }
    }

    private static Vec3 aimedPosition(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(128));
        BlockHitResult hit = player.level().clip(new ClipContext(
            eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getLocation() : end;
    }

    private static void stab(ServerPlayer player, int kills) {
        UUID id = player.getUUID();
        long cooldown = kills >= 10 ? 200 : kills >= 7 ? 300 : kills >= 3 ? 600 : 1200;
        if (NEXT_STAB.getOrDefault(id, 0L) > ticks) {
            player.sendSystemMessage(Component.literal("Stab cooling down").withStyle(ChatFormatting.RED)); return;
        }
        if (STAB_CHARGES.getOrDefault(id, 0) <= 0) {
            player.sendSystemMessage(Component.literal("No stab charges: earn 2 per PvP kill").withStyle(ChatFormatting.RED)); return;
        }
        Vec3 target = aimedPosition(player);
        ACTIVE_STABS.add(new StabWave(player, target));
        STAB_CHARGES.put(id, STAB_CHARGES.get(id) - 1);
        NEXT_STAB.put(id, ticks + cooldown);
        player.sendSystemMessage(Component.literal("GOD MACE STAB! Remaining: " + STAB_CHARGES.get(id)).withStyle(ChatFormatting.RED));
    }

    private static void strike(ServerPlayer player, int kills) {
        UUID id = player.getUUID();
        if (NEXT_STRIKE.getOrDefault(id, 0L) > ticks) return;
        if (NUKE_CHARGES.getOrDefault(id, 0) <= 0) {
            player.sendSystemMessage(Component.literal("No nuke charges: earn 2 per PvP kill").withStyle(ChatFormatting.RED)); return;
        }
        Vec3 target = aimedPosition(player);
        int rings = Math.min(20, Math.max(1, kills));
        if (!callOrbital("summonNuke", player.level(),
                (int)Math.floor(target.x), (int)Math.min(player.level().getMaxY() - 8, Math.floor(target.y) + 40), (int)Math.floor(target.z), rings)) {
            player.sendSystemMessage(Component.literal("OrbitalStrike+ is missing or incompatible!").withStyle(ChatFormatting.RED));
            return;
        }
        NUKE_CHARGES.put(id, NUKE_CHARGES.get(id) - 1);
        NEXT_STRIKE.put(id, ticks + 20);
        player.sendSystemMessage(Component.literal("OrbitalStrike+ nuke! Rings: " + rings + " | Charges: " + NUKE_CHARGES.get(id)).withStyle(ChatFormatting.GOLD));
    }
}
