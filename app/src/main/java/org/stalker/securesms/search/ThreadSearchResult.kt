package org.stalker.securesms.search

import org.stalker.securesms.database.model.ThreadRecord

data class ThreadSearchResult(val results: List<ThreadRecord>, val query: String)
