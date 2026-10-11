package lol.osm.streettally.campaign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CampaignLinkTest {
    @Test fun readsAppAndWebLinks() {
        assertEquals(3L, CampaignLink.challenge("mrchoice://challenge/3"))
        assertEquals(3L, CampaignLink.challenge("MRCHOICE://Challenge/3/"))
        assertEquals(42L, CampaignLink.challenge("https://streettally.osm.lol/challenge/42"))
        assertEquals(42L, CampaignLink.challenge("https://example.org/challenge/42?utm=x"))
    }

    @Test fun rejectsOtherLinks() {
        listOf(
            "http://streettally.osm.lol/challenge/42", "mrchoice://challenge/0", "mrchoice://challenge/-1",
            "mrchoice://challenge/abc", "mrchoice://task/3", "mrchoice://challenge/3/extra",
            "https://streettally.osm.lol/", "lol.osm.maproulette.choice:/oauth2redirect", "not a url", "",
        ).forEach { assertNull(it, CampaignLink.challenge(it)) }
    }

    @Test fun buildsLinks() {
        assertEquals("mrchoice://challenge/3", CampaignLink.url(3))
        assertEquals("https://streettally.osm.lol/challenge/3", CampaignLink.webUrl(3, "streettally.osm.lol"))
        assertEquals(3L, CampaignLink.challenge(CampaignLink.webUrl(3, "streettally.osm.lol")))
    }

    @Test fun previewFlagIsReadFromTheQuery() {
        assertTrue(CampaignLink.isPreview("https://streettally.osm.lol/challenge/42?preview=1"))
        assertTrue(CampaignLink.isPreview("mrchoice://challenge/42?utm=x&preview=1"))
        assertFalse(CampaignLink.isPreview("mrchoice://challenge/42"))
        assertFalse(CampaignLink.isPreview("mrchoice://challenge/42?preview=0"))
        assertEquals(42L, CampaignLink.challenge("mrchoice://challenge/42?preview=1"))
    }
}
