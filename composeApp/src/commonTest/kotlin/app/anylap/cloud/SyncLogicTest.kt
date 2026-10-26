package app.anylap.cloud

import app.anylap.data.Racer
import app.anylap.data.Session
import app.anylap.data.SessionConfig
import app.anylap.data.Sport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncLogicTest {
    private val me = "me"
    private fun racer(id: String, name: String = id, owner: String? = null) =
        Racer(id = id, name = name, sport = Sport.RC, number = 1, color = 0, photoPath = "/local/$id.jpg", fingerprint = listOf(0.1f), ownerUserId = owner)

    private fun session(id: String, racers: List<Racer> = emptyList(), timedBy: String? = null) = Session(
        id = id, startedAt = 1, config = SessionConfig(), racers = racers, laps = emptyList(),
        durationMs = 0, order = emptyList(), videoPath = "/video/$id.mp4", timedByUserId = timedBy,
    )

    @Test
    fun uploadsNeverContainLocalFilesOrRaceFingerprints() {
        val s = SyncLogic.cloudSession(session("s", listOf(racer("a"))))
        assertEquals(null, s.videoPath)
        assertEquals(null, s.racers.single().photoPath)
        assertTrue(s.racers.single().fingerprint.isEmpty())
        val r = SyncLogic.cloudRacer(racer("a"))
        assertEquals(null, r.photoPath)
        assertEquals(listOf(0.1f), r.fingerprint)
    }

    @Test
    fun hashIgnoresLocalOnlyFields() {
        assertEquals(SyncLogic.hash(racer("a")), SyncLogic.hash(racer("a").copy(photoPath = "/elsewhere.jpg")))
        assertEquals(SyncLogic.hash(session("s")), SyncLogic.hash(session("s").copy(videoPath = null)))
    }

    @Test
    fun pushesOnlyChangedOwnRacers() {
        val a = racer("a"); val b = racer("b"); val borrowed = racer("c", owner = "friend")
        val state = SyncState(racerHashes = mapOf("a" to SyncLogic.hash(a)))
        assertEquals(listOf("b"), SyncLogic.racersToPush(listOf(a, b, borrowed), state, me).map { it.id })
        assertEquals(listOf("a"), SyncLogic.racersToPush(listOf(a.copy(name = "Fast")), state, me).map { it.id })
    }

    @Test
    fun pushesOnlySessionsTimedHere() {
        val mine = session("s1"); val friends = session("s2", timedBy = "friend")
        assertEquals(listOf("s1"), SyncLogic.sessionsToPush(listOf(mine, friends), SyncState(), me).map { it.id })
    }

    @Test
    fun localDeletesOfOwnSessionsArePushedButFriendsAreNot() {
        val state = SyncState(sessionHashes = mapOf("own" to "x", "friend" to "y"), foreignSessions = setOf("friend"))
        assertEquals(setOf("own"), SyncLogic.sessionsDeletedLocally(emptyList(), state))
    }

    @Test
    fun remoteWinsOnlyWhenLocalIsUnchanged() {
        assertTrue(SyncLogic.acceptRemote<String>(null, null, null))
        assertTrue(SyncLogic.acceptRemote("x", "h1", "h1"))
        assertFalse(SyncLogic.acceptRemote("x", "h2", "h1"))
    }

    @Test
    fun tagsOwnUploadedAndBorrowedRacers() {
        val s = session("s", listOf(racer("mine"), racer("notUploaded"), racer("borrowed", owner = "friend")))
        assertEquals(listOf("mine", "borrowed"), SyncLogic.taggableRacers(s, setOf("mine")))
    }

    @Test
    fun oldJsonWithoutNewFieldsStillLoads() {
        val old = """{"id":"r","name":"Car 1","sport":"RC","number":1,"color":0}"""
        assertEquals(null, SyncLogic.json.decodeFromString(Racer.serializer(), old).ownerUserId)
    }
}
