package jp.ivrm.playerbridge.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class PresentationEventsJsonTest {
    private static final UUID PLAYER = UUID.fromString("c389d9ba-ac7c-465e-8fa9-6c3d0840a612");

    @Test
    void eventsRequestBodyMatchesTheApiContract() {
        var body = JsonParser.parseString(PresentationJson.buildEventsRequestBody("main")).getAsJsonObject();
        assertEquals(1, body.get("schemaVersion").getAsInt());
        assertEquals("main", body.get("serverRole").getAsString());
        assertEquals(2, body.size());
    }

    @Test
    void ackRequestBodyCarriesTheEventIds() {
        var body = JsonParser.parseString(
                        PresentationJson.buildAckRequestBody("resource", List.of("event-a", "event-b")))
                .getAsJsonObject();
        assertEquals(1, body.get("schemaVersion").getAsInt());
        assertEquals("resource", body.get("serverRole").getAsString());
        assertEquals(2, body.getAsJsonArray("eventIds").size());
        assertEquals("event-a", body.getAsJsonArray("eventIds").get(0).getAsString());
        assertEquals("event-b", body.getAsJsonArray("eventIds").get(1).getAsString());
    }

    @Test
    void parsesQuestAndAchievementEvents() {
        String json = """
                {
                  "generatedAt": "2026-11-23T12:00:00.000Z",
                  "serverRole": "main",
                  "events": [
                    {
                      "eventId": "mcpe_quest_1",
                      "uuid": "%s",
                      "eventType": "quest_completed",
                      "payload": {
                        "questKey": "mc:daily:mine",
                        "title": "鉱石を掘る",
                        "periodType": "daily",
                        "periodKey": "2026-11-23",
                        "xpReward": 20,
                        "effectProfile": "standard"
                      },
                      "createdAt": "2026-11-23T11:59:00.000Z",
                      "expiresAt": "2026-11-24T00:00:00.000Z"
                    },
                    {
                      "eventId": "mcpe_unlock_1",
                      "uuid": "%s",
                      "eventType": "achievement_unlocked",
                      "payload": {
                        "code": "mining-100",
                        "name": "鉱夫見習い",
                        "rank": "bronze",
                        "tier": "1",
                        "xpReward": 10,
                        "effectProfile": "celebration"
                      },
                      "createdAt": "2026-11-23T11:58:00.000Z",
                      "expiresAt": "2026-11-30T11:58:00.000Z"
                    }
                  ]
                }
                """.formatted(PLAYER, PLAYER);

        List<PresentationEvent> events = PresentationJson.parseEvents(json);
        assertEquals(2, events.size());

        PresentationEvent quest = events.get(0);
        assertEquals("mcpe_quest_1", quest.eventId());
        assertEquals(PLAYER, quest.uuid());
        assertEquals("quest_completed", quest.eventType());
        assertEquals("mc:daily:mine", quest.payload().questKey());
        assertEquals("鉱石を掘る", quest.payload().title());
        assertEquals("daily", quest.payload().periodType());
        assertEquals(Integer.valueOf(20), quest.payload().xpReward());
        assertNull(quest.payload().pointsAmount());
        assertNull(quest.payload().code());
        assertEquals(Instant.parse("2026-11-23T11:59:00.000Z"), quest.createdAt());
        assertEquals(Instant.parse("2026-11-24T00:00:00.000Z"), quest.expiresAt());

        PresentationEvent unlock = events.get(1);
        assertEquals("achievement_unlocked", unlock.eventType());
        assertEquals("mining-100", unlock.payload().code());
        assertEquals("鉱夫見習い", unlock.payload().name());
        assertEquals("bronze", unlock.payload().rank());
        assertEquals("1", unlock.payload().tier());
        assertTrue(unlock.expiresAt().isAfter(unlock.createdAt()));
    }

    @Test
    void parsesLoginBonusEvents() {
        String json = """
                {
                  "generatedAt": "2026-11-24T00:01:00.000Z",
                  "serverRole": "main",
                  "events": [
                    {
                      "eventId": "mcpe_bonus_1",
                      "uuid": "%s",
                      "eventType": "login_bonus",
                      "payload": {"xp": 20, "date": "2026-11-24", "totalXp": 1540},
                      "createdAt": "2026-11-23T15:00:30.000Z",
                      "expiresAt": "2026-11-24T15:00:00.000Z"
                    },
                    {
                      "eventId": "mcpe_bonus_2",
                      "uuid": "%s",
                      "eventType": "login_bonus",
                      "payload": {"xp": 20, "date": "2026-11-24"},
                      "createdAt": "2026-11-23T15:00:30.000Z",
                      "expiresAt": "2026-11-24T15:00:00.000Z"
                    }
                  ]
                }
                """.formatted(PLAYER, PLAYER);

        List<PresentationEvent> events = PresentationJson.parseEvents(json);
        assertEquals(2, events.size());

        PresentationEvent bonus = events.get(0);
        assertEquals("login_bonus", bonus.eventType());
        assertTrue(bonus.isLoginBonus());
        assertEquals(Integer.valueOf(20), bonus.payload().xp());
        assertEquals("2026-11-24", bonus.payload().date());
        assertEquals(Integer.valueOf(1540), bonus.payload().totalXp());
        assertNull(bonus.payload().xpReward());

        PresentationEvent noTotal = events.get(1);
        assertTrue(noTotal.isLoginBonus());
        assertNull(noTotal.payload().totalXp());
    }

    @Test
    void missingOptionalPayloadFieldsParseAsNull() {
        String json = """
                {
                  "generatedAt": "2026-11-23T12:00:00.000Z",
                  "serverRole": "main",
                  "events": [
                    {
                      "eventId": "mcpe_clear_1",
                      "uuid": "%s",
                      "eventType": "quest_all_clear",
                      "payload": {"periodType": "weekly", "pointsAmount": 5, "effectProfile": "grand"},
                      "createdAt": "2026-11-23T11:59:00.000Z",
                      "expiresAt": "2026-11-30T00:00:00.000Z"
                    }
                  ]
                }
                """.formatted(PLAYER);

        PresentationEvent event = PresentationJson.parseEvents(json).get(0);
        assertEquals("quest_all_clear", event.eventType());
        assertNull(event.payload().title());
        assertNull(event.payload().xpReward());
        assertEquals(Integer.valueOf(5), event.payload().pointsAmount());
        assertEquals("grand", event.payload().effectProfile());
    }

    @Test
    void malformedBodiesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> PresentationJson.parseEvents("{}"));
        assertThrows(IllegalArgumentException.class, () -> PresentationJson.parseEvents("not json"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PresentationJson.parseEvents(
                        "{\"generatedAt\":\"2026-11-23T12:00:00.000Z\",\"serverRole\":\"main\",\"events\":[{}]}"));
    }
}
