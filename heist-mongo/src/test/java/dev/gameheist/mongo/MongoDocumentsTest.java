package dev.gameheist.mongo;

import dev.gameheist.domain.player.*;
import org.junit.jupiter.api.Test;
import org.bson.Document;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MongoDocumentsTest {
    @Test void roundTripsEveryPresetAndSetting() {
        var profile = new PlayerProfile(UUID.randomUUID(), 42, List.of(Loadout.starter(Role.SCOUT),
                Loadout.starter(Role.SUPPORT), Loadout.starter(Role.ENFORCER)), 2, new PlayerSettings("en", false, true));
        assertEquals(profile, MongoDocuments.profile(Document.parse(MongoDocuments.profile(profile).toJson())));
    }
    @Test void rejectsFutureSchemaInsteadOfResettingProgress() {
        var document = MongoDocuments.profile(PlayerProfile.starter(UUID.randomUUID()));
        document.put("schemaVersion", 2);
        assertThrows(IllegalStateException.class, () -> MongoDocuments.profile(document));
    }
}
