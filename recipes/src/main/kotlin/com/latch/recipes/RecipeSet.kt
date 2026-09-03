package com.latch.recipes

import com.latch.core.model.Recipe

/**
 * The set the app offers: FR-602's built-ins, with any the user has shadowed replaced and their
 * own appended.
 *
 * Pure, so the shadowing rule is testable and so the ordering is a decision rather than
 * whatever a map iteration produced. Built-ins keep their shipped order — a user learns where
 * "Meeting + prep" is in the list — and the user's own follow, newest last.
 */
fun recipesFor(builtIn: List<Recipe>, stored: List<Recipe>): List<Recipe> {
    val byId = stored.associateBy { it.id }
    val shadowed = builtIn.map { byId[it.id] ?: it }
    val own = stored.filterNot { own -> builtIn.any { it.id == own.id } }
    return shadowed + own
}
