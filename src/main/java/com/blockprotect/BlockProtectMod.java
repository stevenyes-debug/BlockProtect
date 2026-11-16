package com.blockprotect;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class BlockProtectMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("blockprotect");

    private static final Identifier BREAK_SPEED_ID = Identifier.fromNamespaceAndPath("blockprotect", "break_speed_lock");
    private static final AttributeModifier BREAK_SPEED_LOCK = new AttributeModifier(BREAK_SPEED_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    private static final Set<UUID> ADD_SELECTION = new HashSet<>();
    private static final Set<UUID> REMOVE_SELECTION = new HashSet<>();
    private static BlockProtectConfig config;

    @Override
    public void onInitialize() {
        config = BlockProtectConfig.load();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("protectblock")
                    .requires(BlockProtectMod::isOp)
                    .executes(context -> startAdd(context.getSource()))
                    .then(Commands.literal("add").executes(context -> startAdd(context.getSource())))
                    .then(Commands.literal("remove").executes(context -> startRemove(context.getSource())))
                    .then(Commands.literal("list").executes(context -> list(context.getSource())))
                    .then(Commands.literal("status").executes(context -> status(context.getSource())))
                    .then(Commands.literal("cancel").executes(context -> cancel(context.getSource())))
                    .then(Commands.literal("off").executes(context -> cancel(context.getSource())))
                    .then(Commands.literal("clear").executes(context -> clear(context.getSource()))));
            dispatcher.register(Commands.literal("unprotectblock")
                    .requires(BlockProtectMod::isOp)
                    .executes(context -> startRemove(context.getSource())));
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                updateBreakSpeedLock(player);
            }
        });
        UseBlockCallback.EVENT.register(BlockProtectMod::onUseBlock);
        PlayerBlockBreakEvents.BEFORE.register(BlockProtectMod::onBlockBreak);
        LOGGER.info("[BlockProtect] Ready. Use /protectblock then right-click a block.");
    }

    private static int startAdd(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command can only be used by a player."));
            return 0;
        }
        UUID id = player.getUUID();
        ADD_SELECTION.add(id);
        REMOVE_SELECTION.remove(id);
        player.displayClientMessage(Component.literal("§a[BlockProtect] 右键一个方块来保护它"), false);
        return 1;
    }

    private static int startRemove(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command can only be used by a player."));
            return 0;
        }
        UUID id = player.getUUID();
        REMOVE_SELECTION.add(id);
        ADD_SELECTION.remove(id);
        player.displayClientMessage(Component.literal("§c[BlockProtect] 右键一个已保护的方块来取消保护"), false);
        return 1;
    }

    private static int cancel(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command can only be used by a player."));
            return 0;
        }
        UUID id = player.getUUID();
        ADD_SELECTION.remove(id);
        REMOVE_SELECTION.remove(id);
        player.displayClientMessage(Component.literal("§e[BlockProtect] 已取消右键选择模式"), false);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        String mode = "无";
        if (player != null) {
            UUID id = player.getUUID();
            if (ADD_SELECTION.contains(id)) {
                mode = "等待右键保护一个方块";
            } else if (REMOVE_SELECTION.contains(id)) {
                mode = "等待右键取消保护一个方块";
            }
        }
        String finalMode = mode;
        source.sendSuccess(() -> Component.literal("[BlockProtect] 当前模式: " + finalMode), false);
        source.sendSuccess(() -> Component.literal("[BlockProtect] 已保护方块数量: " + config.size()), false);
        return config.size();
    }

    private static int list(CommandSourceStack source) {
        List<String> entries = config.entries();
        source.sendSuccess(() -> Component.literal("[BlockProtect] 已保护 " + entries.size() + " 个方块"), false);
        int shown = 0;
        for (String entry : entries) {
            if (shown >= 10) {
                source.sendSuccess(() -> Component.literal("[BlockProtect] ... 其余 " + (entries.size() - 10) + " 个未显示"), false);
                break;
            }
            source.sendSuccess(() -> Component.literal("§7- " + entry), false);
            shown++;
        }
        return entries.size();
    }

    private static int clear(CommandSourceStack source) {
        int oldSize = config.size();
        config.clear();
        source.sendSuccess(() -> Component.literal("[BlockProtect] 已清除 " + oldSize + " 个保护方块"), false);
        return oldSize;
    }

    private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hitResult) {
        if (level.isClientSide()) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            UUID id = serverPlayer.getUUID();
            boolean adding = ADD_SELECTION.remove(id);
            boolean removing = REMOVE_SELECTION.remove(id);
            if (adding) {
                BlockPos pos = hitResult.getBlockPos();
                if (config.add(level, pos)) {
                    send(serverPlayer, "§a[BlockProtect] 已保护方块 " + formatPos(pos));
                } else {
                    send(serverPlayer, "§e[BlockProtect] 这个方块已经被保护了");
                }
                return InteractionResult.SUCCESS;
            }
            if (removing) {
                BlockPos pos = hitResult.getBlockPos();
                if (config.remove(level, pos)) {
                    send(serverPlayer, "§c[BlockProtect] 已取消保护方块 " + formatPos(pos));
                } else {
                    send(serverPlayer, "§e[BlockProtect] 这个方块没有被保护");
                }
                return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.PASS;
    }

    private static boolean onBlockBreak(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        if (config.isProtected(level, pos) || isSupportOfProtectedSign(level, pos)) {
            if (player instanceof ServerPlayer serverPlayer) {
                send(serverPlayer, "§c[BlockProtect] 这个方块已被保护，无法破坏");
            }
            return false;
        }
        return true;
    }

    private static boolean isSupportOfProtectedSign(Level level, BlockPos brokenPos) {
        for (Direction direction : Direction.values()) {
            BlockPos signPos = brokenPos.relative(direction);
            if (config.isProtected(level, signPos) == false) {
                continue;
            }
            BlockState signState = level.getBlockState(signPos);
            if (signState.getBlock() instanceof SignBlock) {
                if (isSupportForSign(signPos, signState, brokenPos)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isSupportForSign(BlockPos signPos, BlockState signState, BlockPos brokenPos) {
        if (signState.getBlock() instanceof StandingSignBlock) {
            return brokenPos.equals(signPos.below());
        }
        if (signState.getBlock() instanceof WallSignBlock) {
            Direction facing = signState.getValue(WallSignBlock.FACING);
            return brokenPos.equals(signPos.relative(facing.getOpposite()));
        }
        if (signState.getBlock() instanceof CeilingHangingSignBlock) {
            int dy = brokenPos.getY() - signPos.getY();
            return dy > 0 && dy <= 8 && brokenPos.getX() == signPos.getX() && brokenPos.getZ() == signPos.getZ();
        }
        if (signState.getBlock() instanceof WallHangingSignBlock) {
            Direction facing = signState.getValue(WallHangingSignBlock.FACING);
            if (brokenPos.equals(signPos.relative(facing.getOpposite()))) {
                return true;
            }
            int dy = brokenPos.getY() - signPos.getY();
            return dy > 0 && dy <= 8 && brokenPos.getX() == signPos.getX() && brokenPos.getZ() == signPos.getZ();
        }
        return false;
    }

    private static void updateBreakSpeedLock(ServerPlayer player) {
        AttributeInstance attribute = player.getAttribute(Attributes.BLOCK_BREAK_SPEED);
        if (attribute == null) {
            return;
        }
        HitResult hitResult = player.pick(player.blockInteractionRange(), 1.0F, false);
        boolean lock = false;
        if (hitResult instanceof BlockHitResult blockHitResult) {
            BlockPos pos = blockHitResult.getBlockPos();
            lock = config.isProtected(player.level(), pos) || isSupportOfProtectedSign(player.level(), pos);
        }
        if (lock) {
            if (attribute.hasModifier(BREAK_SPEED_ID) == false) {
                attribute.addTransientModifier(BREAK_SPEED_LOCK);
            }
        } else {
            if (attribute.hasModifier(BREAK_SPEED_ID)) {
                attribute.removeModifier(BREAK_SPEED_ID);
            }
        }
    }

    private static boolean isOp(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return true;
        }
        return source.getServer().getPlayerList().isOp(player.nameAndId());
    }

    private static void send(ServerPlayer player, String message) {
        player.displayClientMessage(Component.literal(message), false);
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}
