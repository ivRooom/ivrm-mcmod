package jp.ivrm.playerbridge.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class EffectTextTest {
    private static final UUID PLAYER = UUID.fromString("c389d9ba-ac7c-465e-8fa9-6c3d0840a612");
    private static final Instant CREATED = Instant.parse("2026-11-23T11:59:00.000Z");
    private static final Instant EXPIRES = Instant.parse("2026-11-24T00:00:00.000Z");

    private static PresentationEvent quest(String periodType, String profile, Integer xp, Integer points) {
        String type = points != null ? "quest_all_clear" : "quest_completed";
        return new PresentationEvent(
                "evt-1",
                PLAYER,
                type,
                new PresentationEvent.Payload(
                        "mc:daily:mine", "鉱石を掘る", periodType, "2026-11-23", xp, points,
                        null, null, null, null, profile, null, null, null),
                CREATED,
                EXPIRES);
    }

    private static PresentationEvent unlock(String profile) {
        return new PresentationEvent(
                "evt-2",
                PLAYER,
                "achievement_unlocked",
                new PresentationEvent.Payload(
                        null, null, null, null, 10, null,
                        "mining-100", "鉱夫見習い", "bronze", "1", profile, null, null, null),
                CREATED,
                EXPIRES);
    }

    private static PresentationEvent loginBonus(String date, Integer xp, Integer totalXp) {
        return new PresentationEvent(
                "evt-3",
                PLAYER,
                "login_bonus",
                new PresentationEvent.Payload(
                        null, null, null, null, null, null,
                        null, null, null, null, null,
                        xp, date, totalXp),
                CREATED,
                EXPIRES);
    }

    @Test
    void liveDailyCompletionIsAnActionBar() {
        EffectText.RenderPlan plan = EffectText.planLive(quest("daily", "standard", 20, null), true);
        assertEquals(EffectText.Presentation.ACTION_BAR, plan.presentation());
        assertTrue(plan.actionBarText().contains("鉱石を掘る"));
        assertEquals(EffectText.SoundProfile.SMALL, plan.sound());
        assertEquals(EffectText.ParticleProfile.NONE, plan.particles());
        assertEquals(1, plan.chatLines().size());
    }

    @Test
    void liveWeeklyCompletionCelebrates() {
        EffectText.RenderPlan plan = EffectText.planLive(quest("weekly", "celebration", 50, null), true);
        assertEquals(EffectText.Presentation.ACTION_BAR, plan.presentation());
        assertEquals(EffectText.SoundProfile.LEVELUP, plan.sound());
        assertEquals(EffectText.ParticleProfile.SMALL, plan.particles());
    }

    @Test
    void liveAllClearIsAGrandTitle() {
        EffectText.RenderPlan plan = EffectText.planLive(quest("daily", "grand", null, 1), true);
        assertEquals(EffectText.Presentation.TITLE, plan.presentation());
        assertTrue(plan.titleText().contains("全完了"));
        assertEquals(EffectText.SoundProfile.CHALLENGE, plan.sound());
        assertEquals(EffectText.ParticleProfile.FIREWORK, plan.particles());
    }

    @Test
    void liveUnlockIsATitleGradedByProfile() {
        EffectText.RenderPlan celebration = EffectText.planLive(unlock("celebration"), true);
        assertEquals(EffectText.Presentation.TITLE, celebration.presentation());
        assertTrue(celebration.titleText().contains("アチーブメント解除"));
        assertTrue(celebration.subtitleText().contains("鉱夫見習い"));
        assertEquals(EffectText.SoundProfile.LEVELUP, celebration.sound());

        EffectText.RenderPlan grand = EffectText.planLive(unlock("grand"), true);
        assertEquals(EffectText.SoundProfile.CHALLENGE, grand.sound());
        assertEquals(EffectText.ParticleProfile.FIREWORK, grand.particles());
    }

    @Test
    void effectsOffFallsBackToTheChatRecordOnly() {
        EffectText.RenderPlan plan = EffectText.planLive(unlock("grand"), false);
        assertEquals(EffectText.Presentation.CHAT_ONLY, plan.presentation());
        assertNull(plan.titleText());
        assertEquals(EffectText.SoundProfile.NONE, plan.sound());
        assertEquals(EffectText.ParticleProfile.NONE, plan.particles());
        assertEquals(1, plan.chatLines().size());
        assertTrue(plan.chatLines().get(0).contains("鉱夫見習い"));
    }

    @Test
    void catchUpUnlockIsOneSimplifiedTitle() {
        EffectText.RenderPlan plan = EffectText.planCatchUp(unlock("grand"), true);
        assertEquals(EffectText.Presentation.TITLE, plan.presentation());
        assertEquals(EffectText.SoundProfile.SMALL, plan.sound());
        assertEquals(EffectText.ParticleProfile.NONE, plan.particles());
    }

    @Test
    void catchUpQuestCompletionIsChatOnly() {
        EffectText.RenderPlan plan = EffectText.planCatchUp(quest("daily", "standard", 20, null), true);
        assertEquals(EffectText.Presentation.CHAT_ONLY, plan.presentation());
        assertTrue(plan.chatLines().get(0).contains("オフライン中"));
    }

    @Test
    void catchUpAllClearIsChatOnlyToo() {
        EffectText.RenderPlan plan = EffectText.planCatchUp(quest("weekly", "grand", null, 5), true);
        assertEquals(EffectText.Presentation.CHAT_ONLY, plan.presentation());
        assertEquals(EffectText.SoundProfile.NONE, plan.sound());
    }

    @Test
    void todaysLoginBonusIsAnActionBarWithJingleAndSparkles() {
        LocalDate today = LocalDate.of(2026, 11, 24);
        EffectText.RenderPlan plan = EffectText.planLoginBonus(loginBonus("2026-11-24", 20, 1540), true, today);
        assertEquals(EffectText.Presentation.ACTION_BAR, plan.presentation());
        assertEquals("ログインボーナス +20 XP", plan.actionBarText());
        assertEquals(EffectText.SoundProfile.LOGIN_BONUS, plan.sound());
        assertEquals(EffectText.ParticleProfile.LOGIN_BONUS, plan.particles());
        assertEquals(1, plan.chatLines().size());
        assertEquals("ログインボーナス +20 XP を受け取りました！（現在の合計 XP: 1540）", plan.chatLines().get(0));
    }

    @Test
    void loginBonusWithoutTotalOmitsTheSuffix() {
        LocalDate today = LocalDate.of(2026, 11, 24);
        EffectText.RenderPlan plan = EffectText.planLoginBonus(loginBonus("2026-11-24", 20, null), true, today);
        assertEquals(EffectText.Presentation.ACTION_BAR, plan.presentation());
        assertEquals("ログインボーナス +20 XP を受け取りました！", plan.chatLines().get(0));
    }

    @Test
    void staleLoginBonusIsChatOnly() {
        LocalDate today = LocalDate.of(2026, 11, 24);
        EffectText.RenderPlan plan = EffectText.planLoginBonus(loginBonus("2026-11-23", 20, 1540), true, today);
        assertEquals(EffectText.Presentation.CHAT_ONLY, plan.presentation());
        assertNull(plan.actionBarText());
        assertEquals(EffectText.SoundProfile.NONE, plan.sound());
        assertEquals(EffectText.ParticleProfile.NONE, plan.particles());
        assertEquals(1, plan.chatLines().size());
        assertTrue(plan.chatLines().get(0).contains("ログインボーナス +20 XP"));
    }

    @Test
    void loginBonusWithEffectsOffIsChatOnly() {
        LocalDate today = LocalDate.of(2026, 11, 24);
        EffectText.RenderPlan plan = EffectText.planLoginBonus(loginBonus("2026-11-24", 20, 1540), false, today);
        assertEquals(EffectText.Presentation.CHAT_ONLY, plan.presentation());
        assertEquals(EffectText.SoundProfile.NONE, plan.sound());
        assertEquals(EffectText.ParticleProfile.NONE, plan.particles());
        assertEquals(1, plan.chatLines().size());
    }

    @Test
    void loginBonusWithUnreadableDateIsChatOnly() {
        LocalDate today = LocalDate.of(2026, 11, 24);
        EffectText.RenderPlan missing = EffectText.planLoginBonus(loginBonus(null, 20, null), true, today);
        assertEquals(EffectText.Presentation.CHAT_ONLY, missing.presentation());
        EffectText.RenderPlan garbled = EffectText.planLoginBonus(loginBonus("24/11/2026", 20, null), true, today);
        assertEquals(EffectText.Presentation.CHAT_ONLY, garbled.presentation());
    }
}
