package jp.ivrm.playerbridge.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.PlayerRef;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.Snapshot;
import org.junit.jupiter.api.Test;

final class PresentationJsonTest {
    private static final UUID LINKED = UUID.fromString("c389d9ba-ac7c-465e-8fa9-6c3d0840a612");
    private static final UUID UNLINKED = UUID.fromString("11111111-2222-4333-8444-555555555555");

    @Test
    void buildsTheExactRequestShapeTheApiValidates() {
        String body = PresentationJson.buildRequestBody(
                "main",
                List.of(new PlayerRef(LINKED, "ivuruGG"), new PlayerRef(UNLINKED, "guest")));
        assertEquals(
                "{\"schemaVersion\":1,\"serverRole\":\"main\",\"players\":["
                        + "{\"uuid\":\"c389d9ba-ac7c-465e-8fa9-6c3d0840a612\",\"name\":\"ivuruGG\"},"
                        + "{\"uuid\":\"11111111-2222-4333-8444-555555555555\",\"name\":\"guest\"}]}",
                body);
    }

    @Test
    void parsesAFullSnapshotIncludingUnlinkedAndNullSections() {
        String body = """
                {
                  "generatedAt": "2026-11-23T12:00:00Z",
                  "server": {"role": "main", "dailyResetsAt": "2026-11-23T15:00:00Z"},
                  "players": [
                    {
                      "uuid": "c389d9ba-ac7c-465e-8fa9-6c3d0840a612",
                      "linked": true,
                      "daily": {
                        "periodKey": "2026-11-23",
                        "completed": 1,
                        "total": 2,
                        "items": [
                          {"questKey": "login", "title": "ログインする", "progress": 1, "target": 1, "completed": true, "xpReward": 50},
                          {"questKey": "mine-iron", "title": "鉄を集める", "progress": 12, "target": 20, "completed": false, "xpReward": 100}
                        ]
                      },
                      "achievements": {
                        "unlocked": 34,
                        "total": 80,
                        "next": [
                          {"code": "mc-mining-gold", "name": "鉱夫への道", "progress": 420, "target": 500, "rank": "gold"}
                        ]
                      }
                    },
                    {
                      "uuid": "11111111-2222-4333-8444-555555555555",
                      "linked": false,
                      "daily": null,
                      "achievements": null
                    },
                    {
                      "uuid": "22222222-3333-4444-8555-666666666666",
                      "linked": true,
                      "daily": null,
                      "achievements": null
                    }
                  ]
                }
                """;

        Snapshot snapshot = PresentationJson.parseSnapshot(body);
        assertEquals(Instant.parse("2026-11-23T12:00:00Z"), snapshot.generatedAt());
        assertEquals("main", snapshot.serverRole());
        assertEquals(Instant.parse("2026-11-23T15:00:00Z"), snapshot.dailyResetsAt());
        assertEquals(3, snapshot.players().size());

        var linked = snapshot.player(LINKED).orElseThrow();
        assertTrue(linked.linked());
        assertEquals(1, linked.daily().completed());
        assertEquals(2, linked.daily().total());
        assertEquals("鉄を集める", linked.daily().items().get(1).title());
        assertEquals(100, linked.daily().items().get(1).xpReward());
        assertEquals(34, linked.achievements().unlocked());
        assertEquals("gold", linked.achievements().next().get(0).rank());

        var unlinked = snapshot.player(UNLINKED).orElseThrow();
        assertFalse(unlinked.linked());
        assertNull(unlinked.daily());
        assertNull(unlinked.achievements());

        // Linked but fail-soft on the API side: sections stay null.
        var failSoft = snapshot.players().get(2);
        assertTrue(failSoft.linked());
        assertNull(failSoft.daily());
    }

    @Test
    void acceptsNullRankAndIgnoresUnknownFields() {
        String body = """
                {
                  "generatedAt": "2026-11-23T12:00:00Z",
                  "server": {"role": "resource", "dailyResetsAt": "2026-11-23T15:00:00Z", "future": 1},
                  "players": [
                    {
                      "uuid": "c389d9ba-ac7c-465e-8fa9-6c3d0840a612",
                      "linked": true,
                      "daily": {"periodKey": "2026-11-23", "completed": 0, "total": 0, "items": []},
                      "achievements": {
                        "unlocked": 0,
                        "total": 80,
                        "next": [{"code": "x", "name": "y", "progress": 0, "target": 10, "rank": null}]
                      }
                    }
                  ],
                  "futureTopLevel": true
                }
                """;
        Snapshot snapshot = PresentationJson.parseSnapshot(body);
        assertNull(snapshot.player(LINKED).orElseThrow().achievements().next().get(0).rank());
    }

    @Test
    void rejectsMalformedBodies() {
        assertThrows(IllegalArgumentException.class, () -> PresentationJson.parseSnapshot("not-json"));
        assertThrows(IllegalArgumentException.class, () -> PresentationJson.parseSnapshot("{}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PresentationJson.parseSnapshot(
                        "{\"generatedAt\":\"soon\",\"server\":{\"role\":\"main\",\"dailyResetsAt\":\"2026-11-23T15:00:00Z\"},\"players\":[]}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PresentationJson.parseSnapshot(
                        "{\"generatedAt\":\"2026-11-23T12:00:00Z\",\"server\":{\"role\":\"main\",\"dailyResetsAt\":\"2026-11-23T15:00:00Z\"},\"players\":[{\"uuid\":\"nope\",\"linked\":true,\"daily\":null,\"achievements\":null}]}"));
    }
}
