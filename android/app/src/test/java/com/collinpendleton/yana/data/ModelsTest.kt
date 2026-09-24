package com.collinpendleton.yana.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The payloads below are the server's own shapes (internal/server), trimmed. */
class ModelsTest {
    @Test fun signInResponse() {
        val r = YanaJson.decodeFromString(
            SignInResponse.serializer(),
            """{"user":{"id":"01J","username":"sam","is_owner":true},
               "tokens":{"access_token":"a.b","access_expires_at":"2026-09-24T01:02:03.123456789Z",
               "refresh_token":"r","session_id":"s1","refresh_expires_at":"2026-10-24T01:02:03Z"}}""",
        )
        val s = Session.of("https://x/", r.user, r.tokens)
        assertEquals("sam", s.username)
        assertTrue(s.isOwner)
        assertEquals("s1", s.sessionId)
        assertEquals(java.time.Instant.parse("2026-09-24T01:02:03.123Z").toEpochMilli(), s.accessExpiresAt)
    }

    @Test fun authStateWithoutAccounts() {
        val st = YanaJson.decodeFromString(AuthState.serializer(), """{"setup_required":true}""")
        assertTrue(st.setupRequired)
        assertNull(st.user)
    }

    @Test fun spacesIncludingTheRoot() {
        val r = YanaJson.decodeFromString(
            SpacesResponse.serializer(),
            """{"spaces":[{"name":"","label":"","notes":2},{"name":"work","label":"Work","notes":5},{"name":"home","label":"","notes":0}]}""",
        )
        assertEquals(listOf("/", "Work", "home"), r.spaces.map { it.displayName })
    }

    @Test fun treeWithFoldersNotesAndUnknownFields() {
        val r = YanaJson.decodeFromString(
            TreeResponse.serializer(),
            """{"spaces":[{"name":"work","notes":2,"children":[
                {"type":"dir","name":"projects","path":"work/projects","children":[
                  {"type":"note","name":"a.md","path":"work/projects/a.md","id":"01A","title":"Alpha","kind":"md","order":1,"future":"x"}]},
                {"type":"note","name":"b.md","path":"work/b.md","id":"01B","title":"","kind":"md","tags":["t"],"public":true}]}]}""",
        )
        val kids = r.spaces.single().children
        assertTrue(kids[0].isDir)
        assertEquals("Alpha", kids[0].children.single().label)
        assertEquals("b.md", kids[1].label) // an empty title falls back to the file name
        assertTrue(kids[1].public)
    }

    @Test fun noteMetadata() {
        val n = YanaJson.decodeFromString(
            Note.serializer(),
            """{"id":"01A","space":"work","path":"work/a.md","title":"Alpha","preview":"p","kind":"md",
               "content_hash":"h","size":12,"mtime":"2026-09-01T00:00:00Z","created":"2026-09-01T00:00:00Z",
               "updated_at":"2026-09-02T00:00:00Z","trusted":false,"tags":["x"],"base":"work","links":[],
               "html":"<p>hi</p>","markdown":"hi","role":"editor","public":false}""",
        )
        assertEquals("hi", n.markdown)
        assertEquals("editor", n.role)
        assertFalse(n.public)
    }

    @Test fun errorBody() {
        val e = YanaJson.decodeFromString(ApiErrorBody.serializer(), """{"error":"no account exists yet","setup_required":true}""")
        assertTrue(e.setupRequired)
    }
}
