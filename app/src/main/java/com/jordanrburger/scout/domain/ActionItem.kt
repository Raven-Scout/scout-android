package com.jordanrburger.scout.domain

enum class Priority { URGENT, TODO, WATCHING, NONE }

data class ActionItem(
    val prefix: String?,
    val title: String,
    val priority: Priority,
    val done: Boolean,
)
