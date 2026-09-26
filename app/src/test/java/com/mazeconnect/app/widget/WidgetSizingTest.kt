package com.mazeconnect.app.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The layout a dashboard widget shows on the grids people actually have.
 *
 * Sizes are the ones Android documents for a portrait/landscape phone, plus a
 * Samsung 4×5 grid, whose cells are noticeably taller — the case that made a
 * single fixed layout either clip or sprawl.
 */
class WidgetSizingTest {

    // The real, measured sizes — so re-measuring a layout re-runs these.
    private val dashboard = DashboardWidget.layoutsFor(DashboardWidget.Style.GRID)

    private fun pick(w: Int, h: Int): String =
        when (WidgetSizing.pick(w.toFloat(), h.toFloat(), dashboard).key) {
            DashboardWidget.Style.MINI -> "mini"
            DashboardWidget.Style.COMPACT -> "compact"
            DashboardWidget.Style.TILE -> "tile"
            DashboardWidget.Style.DASHBOARD -> "dashboard"
            else -> "large"
        }

    @Test
    fun portraitPhoneGrid() {
        assertEquals("mini", pick(130, 102))      // 2x1
        assertEquals("compact", pick(203, 102))   // 3x1
        assertEquals("compact", pick(276, 102))   // 4x1
        assertEquals("tile", pick(130, 220))      // 2x2
        assertEquals("large", pick(276, 220))     // 4x2
        assertEquals("large", pick(276, 337))     // 4x3
    }

    @Test
    fun landscapePhoneGrid() {
        assertEquals("compact", pick(554, 51))    // 4x1: short, so never clipped
        assertEquals("dashboard", pick(554, 117)) // 4x2
        assertEquals("dashboard", pick(269, 117)) // 2x2
        assertEquals("mini", pick(127, 51))       // 2x1
    }

    @Test
    fun tallSamsungCells() {
        // A 4x1 on a 4x5 grid is tall enough for four meters.
        assertEquals("dashboard", pick(360, 116))
        assertEquals("large", pick(360, 232))
    }

    @Test
    fun detailedKeepsItsListWhenLarge() {
        val detailed = DashboardWidget.layoutsFor(DashboardWidget.Style.DETAILED)
        assertEquals(
            DashboardWidget.Style.DETAILED,
            WidgetSizing.pick(276f, 220f, detailed).key,
        )
    }

    @Test
    fun narrowColumnsGetShortLabels() {
        assertEquals("RAM", DashboardWidget.shortLabel("memory", "Memory"))
        assertEquals("CPU", DashboardWidget.shortLabel("cpu", "CPU"))
        assertEquals("Disk", DashboardWidget.shortLabel("disk", "Disk /"))
        assertEquals("Swap", DashboardWidget.shortLabel("swap", "Swap memory"))
        assertEquals("Temp", DashboardWidget.shortLabel("", "Temperature"))
    }

    @Test
    fun smallerThanEverythingFallsToTheSmallest() {
        assertEquals("mini", pick(60, 30))
    }

    @Test
    fun anExactFitIsChosen() {
        for (layout in dashboard) {
            val chosen = WidgetSizing.pick(layout.widthDp, layout.heightDp, dashboard).key
            assertEquals(layout.key, chosen)
        }
    }
}
