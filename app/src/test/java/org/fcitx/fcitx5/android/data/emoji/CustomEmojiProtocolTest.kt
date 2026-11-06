/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 自定义图像表情容器协议的安全边界 JVM 单测（路径穿越、MIME 白名单、
 * token 校验、清单容错）。资产由用户运行时导入，本工程不预置任何素材。
 */
package org.fcitx.fcitx5.android.data.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomEmojiProtocolTest {

    @Test
    fun mimeWhitelist() {
        assertEquals("image/png", CustomEmojiProtocol.mimeFor("face.png"))
        assertEquals("image/jpeg", CustomEmojiProtocol.mimeFor("a.JPG"))
        assertEquals("image/webp", CustomEmojiProtocol.mimeFor("anim.webp"))
        assertEquals("image/gif", CustomEmojiProtocol.mimeFor("wink.gif"))
        assertNull(CustomEmojiProtocol.mimeFor("payload.apk"))
        assertNull(CustomEmojiProtocol.mimeFor("script.sh"))
        assertNull(CustomEmojiProtocol.mimeFor("noextension"))
        assertTrue(CustomEmojiProtocol.isAllowedMime("image/png"))
        assertFalse(CustomEmojiProtocol.isAllowedMime("application/octet-stream"))
        assertFalse(CustomEmojiProtocol.isAllowedMime("image/svg+xml"))
    }

    @Test
    fun safeFileNameRejectsPathTraversal() {
        assertNull(CustomEmojiProtocol.safeFileName("../../etc/passwd.png"))
        assertNull(CustomEmojiProtocol.safeFileName("..\\..\\evil.png"))
        assertNull(CustomEmojiProtocol.safeFileName("/absolute/a.png"))
        assertNull(CustomEmojiProtocol.safeFileName("."))
        assertNull(CustomEmojiProtocol.safeFileName(".."))
        assertNull(CustomEmojiProtocol.safeFileName("..hidden.png"))
        assertNull(CustomEmojiProtocol.safeFileName("a\u0000b.png"))
        assertNull(CustomEmojiProtocol.safeFileName("x.txt"))
    }

    @Test
    fun safeFileNameSanitizesButKeepsUnicodeLabels() {
        assertNull("含路径分隔符必须拒绝，而不是净化后放行", CustomEmojiProtocol.safeFileName("a/b\\.png"))
        assertEquals("a_b_.png", CustomEmojiProtocol.safeFileName("a:b*.png"))
        assertEquals("微笑.png", CustomEmojiProtocol.safeFileName("微笑.png"))
        assertEquals("a_b.png", CustomEmojiProtocol.safeFileName("a b.png"))
    }

    @Test
    fun packIdValidation() {
        assertEquals("pack_1", CustomEmojiProtocol.safePackId("pack_1"))
        assertNull(CustomEmojiProtocol.safePackId("../escape"))
        assertNull(CustomEmojiProtocol.safePackId("a/b"))
        assertNull(CustomEmojiProtocol.safePackId(""))
        assertNull(CustomEmojiProtocol.safePackId("x".repeat(65)))
    }

    @Test
    fun tokenRoundTrip() {
        val t = CustomEmojiProtocol.token("pack_1", "face.png")
        assertEquals("pack_1" to "face.png", CustomEmojiProtocol.parseToken(t))
        assertTrue(t.startsWith(CustomEmojiProtocol.TOKEN_PREFIX))
    }

    @Test
    fun maliciousTokensAreRejected() {
        assertNull(CustomEmojiProtocol.parseToken("plain text"))
        assertNull(CustomEmojiProtocol.parseToken("${CustomEmojiProtocol.TOKEN_PREFIX}pack_1/../../x.png"))
        assertNull(CustomEmojiProtocol.parseToken("${CustomEmojiProtocol.TOKEN_PREFIX}../etc/x.png"))
        assertNull(CustomEmojiProtocol.parseToken("${CustomEmojiProtocol.TOKEN_PREFIX}pack_1/"))
        assertNull(CustomEmojiProtocol.parseToken("${CustomEmojiProtocol.TOKEN_PREFIX}/face.png"))
        assertNull(CustomEmojiProtocol.parseToken("${CustomEmojiProtocol.TOKEN_PREFIX}pack_1/x.exe"))
    }

    @Test
    fun manifestRoundTrip() {
        val pack = CustomEmojiPack(
            id = "pack_1",
            name = "my stickers",
            createdMs = 1234L,
            items = listOf(
                CustomEmojiItem("a.png", "A", "image/png", 10L),
                CustomEmojiItem("b.gif", "B", "image/gif", 20L)
            )
        )
        val decoded = CustomEmojiProtocol.decodeManifest(CustomEmojiProtocol.encodeManifest(pack))
        assertNotNull(decoded)
        assertEquals(pack.id, decoded!!.id)
        assertEquals(pack.name, decoded.name)
        assertEquals(2, decoded.items.size)
        assertEquals("a.png", decoded.items[0].file)
    }

    @Test
    fun corruptManifestReturnsNull() {
        assertNull(CustomEmojiProtocol.decodeManifest("not json"))
        assertNull(CustomEmojiProtocol.decodeManifest("{}"))
        assertNull(CustomEmojiProtocol.decodeManifest("{\"schema\":999,\"id\":\"p\",\"name\":\"n\"}"))
        assertNull(CustomEmojiProtocol.decodeManifest("{\"schema\":1,\"id\":\"../x\",\"name\":\"n\"}"))
    }

    @Test
    fun manifestFiltersIllegalItemsAndDeduplicates() {
        val text = """
            {"schema":1,"id":"p","name":"n","items":[
              {"file":"ok.png","label":"ok","mime":"image/png","size":1},
              {"file":"../../evil.png","label":"x","mime":"image/png","size":1},
              {"file":"bad.exe","label":"x","mime":"image/png","size":1},
              {"file":"ok.png","label":"dup","mime":"image/png","size":1},
              {"file":"fallback.jpg","label":"","mime":"application/octet-stream","size":1}
            ]}
        """.trimIndent()
        val decoded = CustomEmojiProtocol.decodeManifest(text)!!
        assertEquals(2, decoded.items.size)
        assertEquals(listOf("ok.png", "fallback.jpg"), decoded.items.map { it.file })
        assertEquals("image/jpeg", decoded.items[1].mime)
        assertEquals("fallback", decoded.items[1].label)
    }

    @Test
    fun manifestRespectsItemCap() {
        val items = (1..(CustomEmojiProtocol.MAX_ITEMS_PER_PACK + 30)).joinToString(",") {
            """{"file":"f$it.png","label":"f","mime":"image/png","size":1}"""
        }
        val decoded = CustomEmojiProtocol.decodeManifest(
            """{"schema":1,"id":"p","name":"n","items":[$items]}"""
        )!!
        assertEquals(CustomEmojiProtocol.MAX_ITEMS_PER_PACK, decoded.items.size)
    }

    @Test
    fun labelFromFileName() {
        assertEquals("happy_face", CustomEmojiProtocol.labelFromFileName("happy_face.png"))
        assertEquals("笑脸", CustomEmojiProtocol.labelFromFileName("笑脸.webp"))
        assertEquals("sticker", CustomEmojiProtocol.labelFromFileName(".png"))
    }
}
