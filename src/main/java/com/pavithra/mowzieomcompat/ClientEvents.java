package com.pavithra.mowzieomcompat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Set;

/**
 * Client-side controller that:
 * 1) Detects Mowzie bosses within 30 blocks.
 * 2) Plays the configured boss theme via our SoundEvents (resource pack).
 * 3) Cancels Mowzie's own boss music sounds.
 * 4) While boss music is active, blocks any other MUSIC sounds from starting
 *    (prevents overlaps and avoids breaking OverhauledMusic).
 */
public final class ClientEvents {
    static final int RANGE_BLOCKS = 30;
    static final double RANGE_SQ = (double) RANGE_BLOCKS * (double) RANGE_BLOCKS;

    static final int FADE_IN_TICKS = 40;   // 2s
    static final int FADE_OUT_TICKS = 40;  // 2s

    /** How long to keep the boss track running silently after leaving range (keeps time). */
    private static final int KEEP_SILENT_TICKS = 20 * 60; // 60s

    private static final Set<ResourceLocation> MOWZIE_BOSS_MUSIC = Set.of(
            // Frostmaw
            new ResourceLocation("mowziesmobs", "music.frostmaw_theme"),
            // Ferrous Wroughtnaut
            new ResourceLocation("mowziesmobs", "music.ferrous_wroughtnaut_theme"),
            // Umvuthi
            new ResourceLocation("mowziesmobs", "music.umvuthi_theme"),
            // Sculptor (Tongbi) sections
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_intro"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_level1_1"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_level1_2"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_transition"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_level2_1"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_level2_2"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_level3_1"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_level3_2"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_outro"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_ending"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_combat")
    );

    private BossType currentBoss = null;
    private BossThemeSoundInstance bossSound = null;

    private boolean wasInRange = false;
    private int ticksSinceOutOfRange = 0;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        BossType bossInRange = findBossInRange(player);
        boolean inRange = bossInRange != null;

        if (inRange) {
            ticksSinceOutOfRange = 0;
			// Mark in-range early so PlaySoundEvent can't sneak in a new MUSIC sound
			// during the same tick while the boss track is still fading in.
			wasInRange = true;

            // Boss switched (or first boss acquired)
            if (currentBoss != bossInRange) {
                currentBoss = bossInRange;

                // Kill all other music *once* on boss acquire/switch.
                // We do NOT wrap/replace instances (that breaks OverhauledMusic and causes duplicates).
                stopAllMusic();
                startBossTheme(bossInRange);
                MowzieOMCompat.LOGGER.info("[mowzieomcompat] Boss in range: {} -> playing {}", bossInRange.name(), bossInRange.sound.getId());
            } else {
                // Same boss as before.
                // If the boss track is still running (we kept it silent out-of-range), just fade it back up.
                // If it was hard-stopped, restart it.
                if (bossSound == null || bossSound.isStopped()) {
                    stopAllMusic();
                    startBossTheme(bossInRange);
                    MowzieOMCompat.LOGGER.info("[mowzieomcompat] Re-enter boss range: {} -> restarting {}", bossInRange.name(), bossInRange.sound.getId());
                } else {
                    bossSound.fadeTo(1.0f, FADE_IN_TICKS);
                }
            }

            return;
        }

        // Not in range
        if (wasInRange) {
            // Just left range: fade out, but keep playing silently to preserve time.
            if (bossSound != null) {
                bossSound.fadeTo(0.0f, FADE_OUT_TICKS);
            }
            wasInRange = false;
            ticksSinceOutOfRange = 0;
        }

        if (bossSound != null) {
            ticksSinceOutOfRange++;

            // After a long time away, stop the boss sound completely to free the channel.
            if (ticksSinceOutOfRange >= KEEP_SILENT_TICKS) {
                bossSound.requestHardStopAfterFadeOut();
                bossSound.fadeTo(0.0f, FADE_OUT_TICKS);

                // Clear state shortly after requesting stop (gives time for fade-out).
                if (ticksSinceOutOfRange >= KEEP_SILENT_TICKS + FADE_OUT_TICKS + 20) {
                    bossSound = null;
                    currentBoss = null;
                }
            }
        } else {
            currentBoss = null;
        }
    }

    @SubscribeEvent
    public void onPlaySound(PlaySoundEvent event) {
        SoundInstance sound = event.getSound();
        if (sound == null) return;

        ResourceLocation id = sound.getLocation();

        // Always block Mowzie's boss music (we replace it).
        if (id != null && MOWZIE_BOSS_MUSIC.contains(id)) {
            event.setSound(null);
            return;
        }

        // While boss music is active and audible (or fading), block any other MUSIC from starting.
        if (isBossMusicActive()) {
            if (sound.getSource() == SoundSource.MUSIC) {
                if (id == null || !MowzieOMCompat.MODID.equals(id.getNamespace())) {
                    event.setSound(null);
                }
            }
        }
    }

    private boolean isBossMusicActive() {
        if (bossSound == null) return false;
        // While in-range OR while fading (volume > ~0)
        return wasInRange || bossSound.currentVolume() > 0.01f;
    }

    private void stopAllMusic() {
        Minecraft mc = Minecraft.getInstance();
        try {
            // Common Forge/vanilla pattern: null id => stop all of that source.
            mc.getSoundManager().stop(null, SoundSource.MUSIC);
        } catch (Throwable t) {
            // If mappings differ, still fail gracefully.
            MowzieOMCompat.LOGGER.warn("[mowzieomcompat] Could not stop current music cleanly: {}", t.toString());
        }
    }

    private void startBossTheme(BossType boss) {
        Minecraft mc = Minecraft.getInstance();

        SoundEvent ev;
        try {
            ev = boss.sound.get();
        } catch (Throwable t) {
            MowzieOMCompat.LOGGER.error("[mowzieomcompat] Missing SoundEvent for {}: {}", boss.name(), t.toString());
            return;
        }

        bossSound = new BossThemeSoundInstance(ev);
        mc.getSoundManager().play(bossSound);
    }

    private BossType findBossInRange(Player player) {
        AABB box = player.getBoundingBox().inflate(RANGE_BLOCKS);

        BossType bestType = null;
        double bestDistSq = Double.MAX_VALUE;

        for (Entity e : player.level().getEntities(player, box, ent -> true)) {
            ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
            if (typeId == null) continue;

            for (BossType bt : BossType.values()) {
                if (bt.entityId.equals(typeId)) {
                    double dSq = e.distanceToSqr(player);
                    if (dSq <= RANGE_SQ && dSq < bestDistSq) {
                        bestDistSq = dSq;
                        bestType = bt;
                    }
                }
            }
        }

        return bestType;
    }
}
