package com.jordanrburger.scout.domain

import com.jordanrburger.scout.data.api.ActionItemDto

/** Fixed phone-side rendering order; vault headers carry daily suffix text,
 *  so matching is by emoji prefix (spec §5). */
enum class SectionKind(val emoji: String, val label: String, val collapsedByDefault: Boolean) {
    FOCUS("💡", "Today's Focus", false),
    URGENT("🔴", "Urgent", false),
    TODO("🟡", "To Do", false),
    WATCHING("🟢", "Watching", false),
    DONE("✅", "Recently Completed", true),
}

data class SectionGroup(val kind: SectionKind, val items: List<ActionItem>)

object Sections {

    /** Meetings, run-notes, and unknown sections are hidden on the phone (spec §5);
     *  the headerless "Personal" block folds into the To Do group's tail. */
    fun organize(items: List<ActionItemDto>): List<SectionGroup> {
        val buckets = linkedMapOf<SectionKind, MutableList<ActionItem>>()
        val personalTail = mutableListOf<ActionItem>()

        for (dto in items) {
            val item = dto.toDomain()
            if (dto.section.trim() == "Personal") {
                personalTail.add(item)
                continue
            }
            val kind = SectionKind.entries.firstOrNull { dto.section.startsWith(it.emoji) } ?: continue
            buckets.getOrPut(kind) { mutableListOf() }.add(item)
        }
        if (personalTail.isNotEmpty()) {
            buckets.getOrPut(SectionKind.TODO) { mutableListOf() }.addAll(personalTail)
        }

        return SectionKind.entries
            .mapNotNull { kind -> buckets[kind]?.let { SectionGroup(kind, it) } }
    }

    private fun ActionItemDto.toDomain() = ActionItem(
        prefix = shortPrefix,
        title = title,
        priority = when (priority) {
            "🔴" -> Priority.URGENT
            "🟡" -> Priority.TODO
            "🟢" -> Priority.WATCHING
            else -> Priority.NONE
        },
        done = status == "done",
    )
}
