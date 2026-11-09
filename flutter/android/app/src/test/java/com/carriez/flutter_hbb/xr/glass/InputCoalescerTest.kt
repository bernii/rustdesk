package com.carriez.flutter_hbb.xr.glass

import org.junit.Assert.assertEquals
import org.junit.Test

class InputCoalescerTest {
    private val posted = ArrayList<Runnable>()
    private val sent = ArrayList<Pair<String, Map<String, Any>>>()
    private val output = InputCoalescer(posted::add) { name, values -> sent += name to values }

    @Test fun motionWithinAFrameIsSentOnce() {
        output.move(1f, 2f)
        output.move(3f, 4f)
        assertEquals("Only one frame is asked for.", 1, posted.size)
        assertEquals(emptyList<Any>(), sent)
        posted.single().run()
        assertEquals(listOf("move" to mapOf<String, Any>("dx" to 4.0, "dy" to 6.0)), sent)
    }

    @Test fun flushKeepsMotionAheadOfWhatFollows() {
        output.move(1f, 0f)
        output.flush()
        assertEquals(1, sent.size)
        posted.single().run()
        assertEquals("The frame has nothing left to send.", 1, sent.size)
    }

    @Test fun slowScrollingAccumulatesWholePixelsAndSettleDropsTheRest() {
        repeat(3) {
            output.scroll(0.4f, -0.4f)
            output.flush()
        }
        assertEquals(listOf("scroll" to mapOf<String, Any>("dx" to 1, "dy" to -1)), sent)
        output.settle()
        sent.clear()
        output.scroll(0.4f, 0f)
        output.flush()
        assertEquals("Under a pixel again once the remainder is gone.", emptyList<Any>(), sent)
    }
}
