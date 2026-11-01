package com.grayvines.runway.model

import com.grayvines.runway.data.AppRef

/** A package in a profile: what notification counts are keyed by. */
data class PackageKey(val packageName: String, val profile: Long)

/** The [PackageKey] of the package that owns [AppRef.component] in [AppRef.profile]. */
val AppRef.packageKey: PackageKey
    get() = PackageKey(component.substringBefore('/'), profile)

/**
 * One active notification as the badge counter sees it, stripped of Android types so the counting
 * rules are pure Kotlin.
 */
data class ActiveNotification(
    val packageName: String,
    val profile: Long,
    val number: Int = 0,
    val isGroupSummary: Boolean = false,
    val isOngoing: Boolean = false,
    val isMedia: Boolean = false,
)

/**
 * Notification badge counts per package and profile, following Launcher3's rule: group summaries,
 * ongoing notifications, and media sessions do not count, and each remaining notification adds at
 * least 1 (or its [ActiveNotification.number] when larger). Only positive totals are kept.
 */
fun badgeCounts(notifications: Iterable<ActiveNotification>): Map<PackageKey, Int> {
    val totals = mutableMapOf<PackageKey, Int>()
    for (notification in notifications) {
        if (notification.isGroupSummary || notification.isOngoing || notification.isMedia) continue
        val key = PackageKey(notification.packageName, notification.profile)
        val increment = maxOf(1, notification.number)
        totals[key] = (totals[key] ?: 0) + increment
    }
    return totals
}

/**
 * The badge count to show on [app]: 0 when [app] is in [muted], otherwise its package's count in
 * [counts].
 */
fun badgeCountForApp(
    app: AppRef,
    counts: Map<PackageKey, Int>,
    muted: Set<AppRef> = emptySet(),
): Int = if (app in muted) 0 else counts[app.packageKey] ?: 0

/**
 * The badge count to show on a folder holding [apps]: the sum of the unmuted apps' badge counts.
 */
fun badgeCountForFolder(
    apps: Iterable<AppRef>,
    counts: Map<PackageKey, Int>,
    muted: Set<AppRef> = emptySet(),
): Int = apps.sumOf { badgeCountForApp(it, counts, muted) }

/**
 * Formats a positive badge [count] for display, capping at `"99+"`; `" "`-free empty for `<= 0`.
 */
fun formatBadgeCount(count: Int): String =
    when {
        count <= 0 -> ""
        count > MAX_BADGE_COUNT -> "$MAX_BADGE_COUNT+"
        else -> count.toString()
    }

private const val MAX_BADGE_COUNT = 99
