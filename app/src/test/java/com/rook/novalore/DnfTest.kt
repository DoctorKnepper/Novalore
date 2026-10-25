package com.rook.novalore

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-read "didn't finish" flag: migration, state syncing and CSV round trips. */
class DnfTest {

    // A story finished in 2024, then abandoned on a re-read in 2026.
    private fun reread(): StoreData = StoreData(
        mutableListOf(Work(id = "w", title = "The Shadow Over Innsmouth", author = "H. P. Lovecraft", state = States.DNF)),
        mutableListOf(
            ReadingLog(id = "old", workId = "w", finishedDate = "2024-03-01", rating = 8.0),
            ReadingLog(id = "new", workId = "w", finishedDate = "2026-09-12")
        )
    )

    @Test fun olderFilesFlagOnlyTheLatestRead() {
        val v2 = reread().toJson().put("version", 2)
        for (i in 0 until v2.getJSONArray("logs").length()) v2.getJSONArray("logs").getJSONObject(i).remove("dnf")
        val d = storeDataFromJson(JSONObject(v2.toString())).freeze()
        assertFalse(d.logs.first { it.id == "old" }.dnf)
        assertTrue(d.logs.first { it.id == "new" }.dnf)
    }

    @Test fun currentFilesKeepTheirFlags() {
        val d = reread()
        d.logs.forEach { it.dnf = false }
        val loaded = storeDataFromJson(JSONObject(d.toJson().toString()))
        assertTrue(loaded.logs.none { it.dnf }) // version 3: no guessing
    }

    @Test fun editingTheLatestReadUpdatesTheWork() {
        val d = reread()
        d.inferDnfLogs()
        d.logs.first { it.id == "new" }.dnf = false
        d.syncStateWithLatestLog("w")
        assertEquals(States.READ, d.work("w")!!.state)

        d.logs.first { it.id == "new" }.dnf = true
        d.syncStateWithLatestLog("w")
        assertEquals(States.DNF, d.work("w")!!.state)
    }

    @Test fun deletingTheAbandonedReadFallsBackToTheEarlierOne() {
        val d = reread()
        d.inferDnfLogs()
        d.logs.removeAll { it.id == "new" }
        d.syncStateWithLatestLog("w")
        assertEquals(States.READ, d.work("w")!!.state)
    }

    @Test fun syncLeavesUnfinishedWorksAlone() {
        val d = reread()
        d.work("w")!!.state = States.CURRENTLY
        d.syncStateWithLatestLog("w")
        assertEquals(States.CURRENTLY, d.work("w")!!.state)
    }

    @Test fun csvRoundTripKeepsPerReadFlags() {
        val d = reread().apply { inferDnfLogs() }.freeze()
        val fresh = StoreData()
        Csv.applyImport(fresh, Csv.parseImport(Csv.build(d))!!)
        assertFalse(fresh.logs.first { it.finishedDate == "2024-03-01" }.dnf)
        assertTrue(fresh.logs.first { it.finishedDate == "2026-09-12" }.dnf)
    }

    @Test fun csvWithoutTheColumnFlagsTheLatestRead() {
        val csv = "Title,Author,State,Finished Date,Rating\n" +
            "The Shadow Over Innsmouth,H. P. Lovecraft,DID_NOT_FINISH,2024-03-01,8\n" +
            "The Shadow Over Innsmouth,H. P. Lovecraft,DID_NOT_FINISH,2026-09-12,\n"
        val d = StoreData()
        Csv.applyImport(d, Csv.parseImport(csv)!!)
        assertFalse(d.logs.first { it.finishedDate == "2024-03-01" }.dnf)
        assertTrue(d.logs.first { it.finishedDate == "2026-09-12" }.dnf)
    }
}
