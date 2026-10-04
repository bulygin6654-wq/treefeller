package com.example.treefeller;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class TreeFellerHandler {

    /** Максимум брёвен за одну рубку. */
    private static final int MAX_LOGS = 512;
    /** Максимум листьев за одну рубку. */
    private static final int MAX_LEAVES = 1024;
    /** Максимальная «глубина» листвы от бревна (в ваниле распад идёт при distance 7). */
    private static final int MAX_LEAF_DEPTH = 6;

    /** Защита от рекурсии: gameMode.destroyBlock снова вызывает BreakEvent. */
    private static final Set<UUID> ACTIVE = new HashSet<>();

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (ACTIVE.contains(player.getUUID())) return;

        // Shift — ломаем один блок как обычно
        if (player.isShiftKeyDown()) return;

        // Только с топором (работает и с топорами из других модов)
        ItemStack tool = player.getMainHandItem();
        if (tool.isEmpty() || !tool.canPerformAction(ToolActions.AXE_DIG)) return;

        BlockState state = event.getState();
        if (!state.is(BlockTags.LOGS)) return;

        BlockPos origin = event.getPos();

        // 1. Брёвна текущего дерева (тот же тип бревна, связанные по 26 соседям)
        Set<BlockPos> logs = collectLogs(level, origin, state.getBlock());

        // 2. Естественная листва, которая «держится» на этих брёвнах
        Set<BlockPos> leaves = collectLeaves(level, logs);

        // Нет естественной листвы — это не дерево (например, постройка из брёвен): обычная ломка
        if (leaves.isEmpty()) return;

        ACTIVE.add(player.getUUID());
        try {
            List<BlockPos> extraLogs = new ArrayList<>(logs);
            extraLogs.remove(origin);
            extraLogs.sort(Comparator.comparingDouble(p -> p.distSqr(origin)));

            boolean allLogsBroken = true;
            for (BlockPos pos : extraLogs) {
                if (tool.isEmpty()) {
                    allLogsBroken = false;
                    break;
                }
                // Оставляем топору минимум прочности, чтобы он не сломался посреди рубки
                // (1 ед. ещё спишет сама ваниль за исходное бревно)
                if (tool.isDamageableItem() && tool.getMaxDamage() - tool.getDamageValue() <= 2) {
                    allLogsBroken = false;
                    break;
                }
                // Через gameMode: дроп с инструментом, списание прочности, события защиты/приватов
                if (!player.gameMode.destroyBlock(pos)) {
                    allLogsBroken = false;
                }
            }

            // Листву ломаем только если всё дерево срублено; иначе она опадёт сама
            if (allLogsBroken) {
                for (BlockPos pos : leaves) {
                    if (isNaturalLeaves(level.getBlockState(pos))) {
                        level.destroyBlock(pos, true, player);
                    }
                }
            }
        } finally {
            ACTIVE.remove(player.getUUID());
        }
    }

    private static Set<BlockPos> collectLogs(ServerLevel level, BlockPos origin, Block logBlock) {
        Set<BlockPos> found = new LinkedHashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        BlockPos start = origin.immutable();
        found.add(start);
        queue.add(start);

        while (!queue.isEmpty() && found.size() < MAX_LOGS) {
            BlockPos cur = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos next = cur.offset(dx, dy, dz);
                        if (found.contains(next)) continue;
                        if (!level.getBlockState(next).is(logBlock)) continue;
                        found.add(next);
                        queue.add(next);
                        if (found.size() >= MAX_LOGS) return found;
                    }
                }
            }
        }
        return found;
    }

    private static Set<BlockPos> collectLeaves(ServerLevel level, Set<BlockPos> logs) {
        Map<BlockPos, Integer> depth = new HashMap<>();
        Deque<BlockPos> queue = new ArrayDeque<>();

        // Листья, прилегающие к брёвнам (distance = 1 в ванильной механике)
        for (BlockPos log : logs) {
            for (Direction dir : Direction.values()) {
                BlockPos p = log.relative(dir);
                if (depth.containsKey(p)) continue;
                BlockState s = level.getBlockState(p);
                if (!isNaturalLeaves(s)) continue;
                if (s.hasProperty(LeavesBlock.DISTANCE) && s.getValue(LeavesBlock.DISTANCE) != 1) continue;
                depth.put(p, 1);
                queue.add(p);
            }
        }

        // Дальше идём по цепочке distance = 1, 2, 3 ... — так берутся только листья
        // именно этого дерева, а не соседнего (у него своя цепочка расстояний)
        while (!queue.isEmpty() && depth.size() < MAX_LEAVES) {
            BlockPos cur = queue.poll();
            int d = depth.get(cur);
            if (d >= MAX_LEAF_DEPTH) continue;
            for (Direction dir : Direction.values()) {
                BlockPos n = cur.relative(dir);
                if (depth.containsKey(n)) continue;
                BlockState s = level.getBlockState(n);
                if (!isNaturalLeaves(s)) continue;
                if (s.hasProperty(LeavesBlock.DISTANCE) && s.getValue(LeavesBlock.DISTANCE) != d + 1) continue;
                depth.put(n, d + 1);
                queue.add(n);
                if (depth.size() >= MAX_LEAVES) break;
            }
        }
        return depth.keySet();
    }

    /** Листва по тегу, кроме поставленной игроком (persistent = true). */
    private static boolean isNaturalLeaves(BlockState s) {
        if (!s.is(BlockTags.LEAVES)) return false;
        return !(s.hasProperty(LeavesBlock.PERSISTENT) && s.getValue(LeavesBlock.PERSISTENT));
    }
}
