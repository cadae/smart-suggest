package com.lukecao.suggest.data

import java.security.MessageDigest

/** Equality is all the pending table needs. Never persist the platform's raw tag. */
internal object NotificationKey {
    fun digest(key: String): String = MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
