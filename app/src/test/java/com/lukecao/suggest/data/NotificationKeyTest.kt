package com.lukecao.suggest.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NotificationKeyTest {
    @Test
    fun usesTheFullSha256Digest() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            NotificationKey.digest("abc"),
        )
    }

    @Test
    fun postAndRemovalMatchWithoutStoringTheTag() {
        val key = "0|example.chat|42|private-conversation|10001"
        val stored = NotificationKey.digest(key)
        assertEquals(stored, NotificationKey.digest(String(key.toCharArray())))
        assertFalse(stored.contains("private-conversation"))
        assertEquals(64, stored.length)
    }

    @Test
    fun separateTagsAndUsersRemainDistinctIncludingUnicode() {
        val keys = listOf(
            "0|example.chat|42|conversation-one|10001",
            "0|example.chat|42|conversation-two|10001",
            "10|example.chat|42|conversation-one|10001",
            "0|example.chat|42|会話|10001",
        )
        assertEquals(keys.size, keys.map(NotificationKey::digest).toSet().size)
        assertNotEquals(NotificationKey.digest(""), NotificationKey.digest("会話"))
    }
}
