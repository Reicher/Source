package com.source.self

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DesktopStoreTest {
    private fun ref(id: String, order: Int) = DesktopObjectRef(DESKTOP_OBJECT_BRONZE, id, order)

    @Test fun legacyGroupsAreDiscardedFromPersistedDesktopState() {
        val legacy = JSONObject(
            """{
                "version": 1,
                "items": [
                    {"object_type":"bronze","object_id":"later","group_id":"group-1","order":5},
                    {"object_type":"silver","object_id":"first","group_id":null,"order":2}
                ],
                "groups": [{"id":"group-1","title":"Old group","order":0}],
                "known_objects": ["bronze:archived"]
            }""".trimIndent(),
        )

        val document = desktopDocumentFromJson(legacy)
        val persisted = desktopDocumentToJson(document)

        assertEquals(listOf("first", "later"), document.items.map { it.objectId })
        assertEquals(listOf(0, 1), document.items.map { it.order })
        assertEquals(
            setOf("bronze:archived", "silver:first", "bronze:later"),
            document.knownObjects,
        )
        assertFalse(persisted.has("groups"))
        persisted.getJSONArray("items").let { items ->
            assertFalse(items.getJSONObject(0).has("group_id"))
            assertFalse(items.getJSONObject(1).has("group_id"))
        }
    }

    @Test fun draggedItemCanMoveToAnyDesktopPosition() {
        val items = listOf(ref("first", 0), ref("second", 1), ref("third", 2), ref("fourth", 3))

        assertEquals(
            listOf("first", "third", "fourth", "second"),
            reorderDesktopItems(items, "bronze:second", 3).map { it.objectId },
        )
        assertEquals(
            listOf(0, 1, 2, 3),
            reorderDesktopItems(items, "bronze:second", 3).map { it.order },
        )
    }
}
