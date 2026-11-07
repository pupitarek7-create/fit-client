package net.fit.client.module.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fit.client.module.Category;
import net.fit.client.module.Module;
import net.fit.client.module.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Sus Chunk Finder (Classic method).
 *
 * Crops and plants only finish growing in chunks that are inside someone's simulation distance.
 * We scan loaded chunks for fully grown plants ("heat") and plants that are still growing ("vetoes"),
 * spread both over the simulation radius, and flag chunks where several grown plants line up
 * with no still-growing plant of the same kind nearby.
 *
 * Idea follows the Classic method of the Anubis client (GPL-3.0); code written for Fit Client.
 */
public final class SusChunkFinder extends Module {
    private static final int KELP = 0, CAVE_VINES = 1, VINES = 2, BAMBOO = 3, COCOA = 4, BEE_NESTS = 5;
    private static final int KINDS = 6;

    public final Setting.Num radius = add(new Setting.Num("Simulation Distance", 5, 2, 16, 1));
    public final Setting.Num sensitivity = add(new Setting.Num("Sensitivity", 6, 1, 20, 1));
    public final Setting.Bool kelp = add(new Setting.Bool("Kelp", true));
    public final Setting.Bool caveVines = add(new Setting.Bool("Cave Vines", true));
    public final Setting.Bool vines = add(new Setting.Bool("Vines", true));
    public final Setting.Bool bamboo = add(new Setting.Bool("Bamboo", true));
    public final Setting.Bool cocoa = add(new Setting.Bool("Cocoa", true));
    public final Setting.Bool beeNests = add(new Setting.Bool("Bee Nests", true));
    public final Setting.Bool chatAlerts = add(new Setting.Bool("Chat Alerts", true));

    public record Hit(int chunkX, int chunkZ, int score) { }

    private final Long2IntOpenHashMap[] heat = perKind();
    private final Long2IntOpenHashMap[] vetoes = perKind();
    private final Long2ObjectOpenHashMap<int[]> chunkGrown = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap chunkUngrown = new Long2IntOpenHashMap();
    private final LongOpenHashSet loaded = new LongOpenHashSet();
    private final LongOpenHashSet scanned = new LongOpenHashSet();
    private final LongOpenHashSet queued = new LongOpenHashSet();
    private final LongOpenHashSet alerted = new LongOpenHashSet();
    private final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();

    private ClientLevel lastLevel;
    private int ticks;
    private int appliedRadius = -1;
    private int visibleAge;
    private boolean dirty;
    private volatile List<Hit> hits = List.of();

    public SusChunkFinder() {
        super("Sus Chunks", "Flags chunks where plants finished growing, a sign someone was nearby.", Category.RENDER);
    }

    public List<Hit> hits() { return hits; }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onEnable() {
        resetAll();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            lastLevel = mc.level;
            queueAroundPlayer(mc);
        }
    }

    @Override
    protected void onDisable() {
        resetAll();
        hits = List.of();
    }

    public void onChunkLoad(ClientLevel level, LevelChunk chunk) {
        if (!isEnabled()) return;
        if (level != lastLevel) {
            resetAll();
            lastLevel = level;
        }
        long key = chunk.getPos().toLong();
        loaded.add(key);
        enqueue(key);
    }

    public void onChunkUnload(ClientLevel level, LevelChunk chunk) {
        if (!isEnabled()) return;
        long key = chunk.getPos().toLong();
        loaded.remove(key);
        queued.remove(key);
        forget(key);
    }

    // ---------------------------------------------------------------- ticking

    @Override
    public void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null) return;
        if (level != lastLevel) {
            resetAll();
            lastLevel = level;
            queueAroundPlayer(mc);
        }
        ticks++;

        int r = radius.intValue();
        if (r != appliedRadius) {
            resetAll();
            appliedRadius = r;
            queueAroundPlayer(mc);
        }

        if (ticks % 1200 == 0) {
            LongIterator it = loaded.iterator();
            while (it.hasNext()) enqueue(it.nextLong());
        }

        long deadline = System.nanoTime() + 2_000_000L;
        while (!queue.isEmpty() && System.nanoTime() < deadline) {
            long key = queue.dequeueLong();
            queued.remove(key);
            if (!loaded.contains(key)) continue;
            ChunkAccess access = level.getChunk(ChunkPos.getX(key), ChunkPos.getZ(key), ChunkStatus.FULL, false);
            if (!(access instanceof LevelChunk chunk)) continue;
            updateChunk(key, chunk, level, r);
            scanned.add(key);
            dirty = true;
        }

        visibleAge++;
        if ((dirty && visibleAge >= 5) || visibleAge >= 20) {
            hits = computeHits(mc, r);
            dirty = false;
            visibleAge = 0;
            announceNewHits(mc);
        }
    }

    private void queueAroundPlayer(Minecraft mc) {
        if (mc.level == null || mc.player == null) return;
        int pcx = Math.floorDiv((int) Math.floor(mc.player.getX()), 16);
        int pcz = Math.floorDiv((int) Math.floor(mc.player.getZ()), 16);
        int view = mc.options.getEffectiveRenderDistance() + 2;
        for (int dx = -view; dx <= view; dx++) {
            for (int dz = -view; dz <= view; dz++) {
                ChunkAccess access = mc.level.getChunk(pcx + dx, pcz + dz, ChunkStatus.FULL, false);
                if (access instanceof LevelChunk chunk) {
                    long key = chunk.getPos().toLong();
                    loaded.add(key);
                    enqueue(key);
                }
            }
        }
    }

    private void enqueue(long key) {
        if (queued.add(key)) queue.enqueue(key);
    }

    private void resetAll() {
        for (int i = 0; i < KINDS; i++) {
            heat[i].clear();
            vetoes[i].clear();
        }
        chunkGrown.clear();
        chunkUngrown.clear();
        scanned.clear();
        queued.clear();
        queue.clear();
        loaded.clear();
        alerted.clear();
        hits = List.of();
        dirty = true;
    }

    private void forget(long key) {
        int r = appliedRadius < 0 ? radius.intValue() : appliedRadius;
        withdraw(ChunkPos.getX(key), ChunkPos.getZ(key), r, chunkGrown.remove(key), chunkUngrown.remove(key));
        scanned.remove(key);
        dirty = true;
    }

    // ---------------------------------------------------------------- scanning

    private void updateChunk(long key, LevelChunk chunk, ClientLevel level, int r) {
        int cx = ChunkPos.getX(key);
        int cz = ChunkPos.getZ(key);
        withdraw(cx, cz, r, chunkGrown.remove(key), chunkUngrown.remove(key));

        int[] grown = new int[KINDS];
        int ungrown = 0;
        int counted = countedKinds();

        if (counted != 0) {
            LevelChunkSection[] sections = chunk.getSections();
            int minSection = level.dimensionType().minY() >> 4;
            int minX = cx << 4;
            int minZ = cz << 4;
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

            for (int i = 0; i < sections.length; i++) {
                LevelChunkSection section = sections[i];
                if (section == null || section.hasOnlyAir()) continue;
                if (!section.maybeHas(state -> isCounted(state, counted))) continue;
                int baseY = (minSection + i) << 4;

                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            BlockState state = section.getBlockState(x, y, z);
                            int kind = kindOf(state);
                            if (kind < 0 || (counted & (1 << kind)) == 0) continue;
                            cursor.set(minX + x, baseY + y, minZ + z);
                            int sign = readSign(level, cursor.immutable(), state, kind);
                            if (sign == 1) grown[kind]++;
                            else if (sign == -1) ungrown |= 1 << kind;
                        }
                    }
                }
            }
        }

        chunkGrown.put(key, grown);
        chunkUngrown.put(key, ungrown);
        spread(cx, cz, r, grown, ungrown);
    }

    private int countedKinds() {
        int mask = 0;
        if (kelp.value) mask |= 1 << KELP;
        if (caveVines.value) mask |= 1 << CAVE_VINES;
        if (vines.value) mask |= 1 << VINES;
        if (bamboo.value) mask |= 1 << BAMBOO;
        if (cocoa.value) mask |= 1 << COCOA;
        if (beeNests.value) mask |= 1 << BEE_NESTS;
        return mask;
    }

    private static boolean isCounted(BlockState state, int counted) {
        int kind = kindOf(state);
        return kind >= 0 && (counted & (1 << kind)) != 0;
    }

    private static int kindOf(BlockState state) {
        if (state.is(Blocks.KELP)) return KELP;
        if (state.is(Blocks.CAVE_VINES)) return CAVE_VINES;
        if (state.is(Blocks.VINE)) return VINES;
        if (state.is(Blocks.BAMBOO)) return BAMBOO;
        if (state.is(Blocks.COCOA)) return COCOA;
        if (state.is(Blocks.BEE_NEST)) return BEE_NESTS;
        return -1;
    }

    /** 1 = finished growing, -1 = still growing (blocks the signal), 0 = tells us nothing. */
    private static int readSign(ClientLevel level, BlockPos pos, BlockState state, int kind) {
        switch (kind) {
            case KELP:
                return state.getValue(BlockStateProperties.AGE_25) != 25
                        && level.getBlockState(pos.above()).is(Blocks.WATER) ? -1 : 1;
            case CAVE_VINES:
                return state.getValue(BlockStateProperties.AGE_25) != 25
                        && level.getBlockState(pos.below()).isAir() ? -1 : 1;
            case VINES: {
                BlockState below = level.getBlockState(pos.below());
                if (below.is(Blocks.VINE)) return 0;
                if (!below.isAir()) return 1;
                return hangsFromSide(state) ? -1 : 1;
            }
            case BAMBOO:
                return bambooSign(level, pos, state);
            case COCOA:
                return state.getValue(BlockStateProperties.AGE_2) == 2 ? 1 : -1;
            case BEE_NESTS:
                return state.getValue(BeehiveBlock.HONEY_LEVEL) == 5 ? 1 : -1;
            default:
                return 0;
        }
    }

    private static boolean hangsFromSide(BlockState vine) {
        return vine.getValue(BlockStateProperties.NORTH) || vine.getValue(BlockStateProperties.EAST)
                || vine.getValue(BlockStateProperties.SOUTH) || vine.getValue(BlockStateProperties.WEST);
    }

    private static int bambooSign(ClientLevel level, BlockPos pos, BlockState state) {
        BlockState above = level.getBlockState(pos.above());
        if (above.is(Blocks.BAMBOO)) return 0;
        if (state.getValue(BlockStateProperties.STAGE) == 1) return 1;
        if (above.isAir() && level.getRawBrightness(pos, 0) >= 9) {
            int height = 1;
            while (height < 16 && level.getBlockState(pos.below(height)).is(Blocks.BAMBOO)) height++;
            return height < 16 ? -1 : 0;
        }
        return 0;
    }

    // ---------------------------------------------------------------- heat map

    private void spread(int cx, int cz, int r, int[] grown, int ungrown) {
        for (int kind = 0; kind < KINDS; kind++) {
            Long2IntOpenHashMap kindHeat = heat[kind];
            Long2IntOpenHashMap kindVetoes = vetoes[kind];
            int amount = grown[kind];
            boolean veto = (ungrown & (1 << kind)) != 0;
            if (amount <= 0 && !veto) continue;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    long area = ChunkPos.asLong(cx + dx, cz + dz);
                    if (amount > 0) kindHeat.addTo(area, amount);
                    if (veto) kindVetoes.addTo(area, 1);
                }
            }
        }
    }

    private void withdraw(int cx, int cz, int r, int[] grown, int ungrown) {
        for (int kind = 0; kind < KINDS; kind++) {
            Long2IntOpenHashMap kindHeat = heat[kind];
            Long2IntOpenHashMap kindVetoes = vetoes[kind];
            int amount = grown == null ? 0 : grown[kind];
            boolean veto = (ungrown & (1 << kind)) != 0;
            if (amount <= 0 && !veto) continue;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    long area = ChunkPos.asLong(cx + dx, cz + dz);
                    if (amount > 0) subtract(kindHeat, area, amount);
                    if (veto) subtract(kindVetoes, area, 1);
                }
            }
        }
    }

    private static void subtract(Long2IntOpenHashMap map, long key, int amount) {
        int left = map.get(key) - amount;
        if (left > 0) map.put(key, left);
        else map.remove(key);
    }

    private List<Hit> computeHits(Minecraft mc, int r) {
        Long2IntOpenHashMap total = new Long2IntOpenHashMap();
        for (int kind = 0; kind < KINDS; kind++) {
            Long2IntOpenHashMap kindVetoes = vetoes[kind];
            for (Long2IntMap.Entry entry : heat[kind].long2IntEntrySet()) {
                if (!kindVetoes.containsKey(entry.getLongKey())) total.addTo(entry.getLongKey(), entry.getIntValue());
            }
        }

        Long2IntOpenHashMap eligible = new Long2IntOpenHashMap();
        for (Long2IntMap.Entry entry : total.long2IntEntrySet()) {
            long key = entry.getLongKey();
            if (loaded.contains(key) && scannedNeighbours(ChunkPos.getX(key), ChunkPos.getZ(key)) >= 3) {
                eligible.put(key, entry.getIntValue());
            }
        }

        int threshold = sensitivity.intValue();
        LongArrayList kept = new LongArrayList();
        for (Long2IntMap.Entry entry : eligible.long2IntEntrySet()) {
            if (entry.getIntValue() >= threshold && isPeak(eligible, entry.getLongKey(), entry.getIntValue(), r)) {
                kept.add(entry.getLongKey());
            }
        }

        List<Hit> out = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) {
            long key = kept.getLong(i);
            out.add(new Hit(ChunkPos.getX(key), ChunkPos.getZ(key), eligible.get(key)));
        }

        if (mc.player != null) {
            double px = mc.player.getX() / 16.0;
            double pz = mc.player.getZ() / 16.0;
            out.sort(Comparator.comparingDouble(h -> Math.hypot(h.chunkX() + 0.5 - px, h.chunkZ() + 0.5 - pz)));
        }
        return out;
    }

    private int scannedNeighbours(int cx, int cz) {
        int count = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && scanned.contains(ChunkPos.asLong(cx + dx, cz + dz))) count++;
            }
        }
        return count;
    }

    private static boolean isPeak(Long2IntOpenHashMap field, long key, int value, int reach) {
        int cx = ChunkPos.getX(key);
        int cz = ChunkPos.getZ(key);
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (dx == 0 && dz == 0) continue;
                long other = ChunkPos.asLong(cx + dx, cz + dz);
                int otherValue = field.get(other);
                if (otherValue > value || (otherValue == value && other < key)) return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- output

    private void announceNewHits(Minecraft mc) {
        if (!chatAlerts.value || mc.gui == null) return;
        for (Hit hit : hits) {
            long key = ChunkPos.asLong(hit.chunkX(), hit.chunkZ());
            if (alerted.add(key)) {
                int x = (hit.chunkX() << 4) + 8;
                int z = (hit.chunkZ() << 4) + 8;
                mc.gui.getChat().addMessage(Component.literal(
                        "[Fit] Sus chunk near " + x + ", " + z + " (strength " + hit.score() + ")"));
            }
        }
    }

    @Override
    public void renderHud(GuiGraphics g, Minecraft mc) {
        if (mc.player == null || mc.options.hideGui) return;
        List<Hit> list = hits;
        int shown = Math.min(list.size(), 6);
        int w = 150;
        int h = 14 + Math.max(shown, 1) * 11;
        int x = g.guiWidth() - w - 6;
        int y = 6;

        g.fill(x, y, x + w, y + h, 0xA0140D19);
        g.fill(x, y, x + 2, y + h, 0xFFFF6FB5);
        g.drawString(mc.font, "SUS CHUNKS", x + 7, y + 4, 0xFFFF9CCB, false);

        if (shown == 0) {
            g.drawString(mc.font, "none found", x + 7, y + 15, 0xFF9A8CA8, false);
            return;
        }
        for (int i = 0; i < shown; i++) {
            Hit hit = list.get(i);
            int bx = (hit.chunkX() << 4) + 8;
            int bz = (hit.chunkZ() << 4) + 8;
            int dist = (int) Math.hypot(bx - mc.player.getX(), bz - mc.player.getZ());
            g.drawString(mc.font, bx + ", " + bz + "  " + dist + "m  [" + hit.score() + "]",
                    x + 7, y + 15 + i * 11, 0xFFFFFFFF, false);
        }
    }

    private static Long2IntOpenHashMap[] perKind() {
        Long2IntOpenHashMap[] maps = new Long2IntOpenHashMap[KINDS];
        for (int i = 0; i < KINDS; i++) maps[i] = new Long2IntOpenHashMap();
        return maps;
    }
}
