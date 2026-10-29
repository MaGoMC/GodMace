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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Source port: does not use Bukkit. Must be compiled and tested on Minecraft 26.2. */
public final class GodMace implements ModInitializer {
    private static final String FLAG = "godmace";
    private static final String KILLS = "godmace_kills";
    private static final Map<UUID, Long> NEXT_STAB = new HashMap<>();
    private static final Map<UUID, Long> NEXT_STRIKE = new HashMap<>();
    private static final Map<UUID, Integer> CHARGES = new HashMap<>();
    private static final Map<UUID, Long> LAST_RECHARGE = new HashMap<>();
    private static long ticks;

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
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (ticks % 40 == 0) applyPassiveEffects(player);
                UUID id = player.getUUID();
                long last = LAST_RECHARGE.getOrDefault(id, ticks);
                LAST_RECHARGE.putIfAbsent(id, ticks);
                if (ticks - last >= 600) {
                    CHARGES.put(id, Math.min(3, CHARGES.getOrDefault(id, 3) + 1));
                    LAST_RECHARGE.put(id, ticks);
                }
            }
        });
        // Count direct kills when the victim dies, not merely when the mace hits.
        ServerLivingEntityEvents.AFTER_DEATH.register((victim, damageSource) -> {
            Entity attacker = damageSource.getEntity();
            if (!(attacker instanceof ServerPlayer killer)) return;
            ItemStack mace = killer.getMainHandItem();
            if (!isMace(mace)) return;
            int newKills = getKills(mace) + 1;
            setKills(mace, newKills);
            killer.sendSystemMessage(Component.literal("GOD MACE kills: " + newKills).withStyle(ChatFormatting.GOLD));
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
    private static void stab(ServerPlayer player, int kills) {
        UUID id = player.getUUID();
        long cooldown = kills >= 10 ? 200 : kills >= 7 ? 300 : kills >= 3 ? 600 : 1200;
        if (NEXT_STAB.getOrDefault(id, 0L) > ticks) return;
        NEXT_STAB.put(id, ticks + cooldown);
        ServerLevel level = player.level();
        Vec3 origin = player.getEyePosition();
        Vec3 forward = player.getLookAngle();
        for (Entity entity : level.getEntities(player, new AABB(origin, origin.add(forward.scale(6.5))).inflate(5))) {
            if (entity instanceof LivingEntity living && entity.position().subtract(origin).dot(forward) > 0 && entity.distanceTo(player) < 7) {
                living.hurtServer(level, level.damageSources().playerAttack(player), 6.0f + kills * 2.0f);
                living.push(forward.x * 1.2, 0.4, forward.z * 1.2);
            }
        }
        player.sendSystemMessage(Component.literal("GOD MACE STAB!").withStyle(ChatFormatting.RED));
    }
    private static void strike(ServerPlayer player, int kills) {
        UUID id = player.getUUID();
        if (NEXT_STRIKE.getOrDefault(id, 0L) > ticks || CHARGES.getOrDefault(id, 3) <= 0) return;
        CHARGES.put(id, CHARGES.getOrDefault(id, 3) - 1);
        NEXT_STRIKE.put(id, ticks + 20);
        ServerLevel level = player.level();
        Vec3 center = player.position().add(player.getLookAngle().scale(10));
        int rings = Math.min(8, 1 + kills);
        for (int ring = 0; ring < rings; ring++) {
            double radius = 2.5 * (ring + 1);
            int count = 6 + ring * 4;
            for (int i = 0; i < count; i++) {
                double angle = Math.PI * 2 * i / count;
                PrimedTnt tnt = new PrimedTnt(level, center.x + radius * Math.cos(angle), center.y + 30, center.z + radius * Math.sin(angle), player);
                tnt.setFuse(40 + ring * 5);
                level.addFreshEntity(tnt);
            }
        }
        player.sendSystemMessage(Component.literal("Orbital strike! Charges: " + CHARGES.get(id)).withStyle(ChatFormatting.GOLD));
    }
}
