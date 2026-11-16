package app.ferry

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import app.ferry.core.HostPort
import app.ferry.net.ConnectionState
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TV suite class 6 (plan §4.14) [A18]: the PC address points at an unused port → "Can't find your PC"; **Try again**
 * has focus, so one OK locates again; D-pad right + OK on **Enter address** shows the address field prefilled with
 * the TV's first three octets ("10.0.2." on the emulator). Remote only (`UiDevice.pressDPad*`), no Compose rule.
 */
@RunWith(AndroidJUnit4::class)
class TvPcNotFoundTest {
    private val ctx get() = Probe.context

    private fun locates() = AppGraph.ringLog.snapshot().count { it.contains("locate: ") }

    @Test
    fun pcNotFoundOffersTryAgainAndAddressEntry() {
        LaunchHelpers.cleanPrefs(welcomeDone = false)
        AppGraph.prefs.manual = HostPort.parse("10.0.2.2:18799") // nothing listens there
        LaunchHelpers.launch().use {
            SendHelpers.waitUntil("PC not found", 60_000) { AppGraph.connection.state.value is ConnectionState.NotFound }
            SendHelpers.waitText(ctx.getString(R.string.tv_pc_not_found))
            SendHelpers.waitText(ctx.getString(R.string.try_again))
            SendHelpers.settle()
            Shots.take("tv-pc-not-found")

            // Try again has focus: one OK = one more locate.
            val before = locates()
            SendHelpers.device.pressDPadCenter()
            SendHelpers.waitUntil("located again", 30_000) {
                locates() > before && AppGraph.connection.state.value is ConnectionState.NotFound
            }
            Probe.record("locates", "$before → ${locates()}")
            SendHelpers.waitText(ctx.getString(R.string.enter_address))
            Thread.sleep(1_000) // Try again gets focus again after the screen came back

            // Enter address: number keyboard, prefilled with the TV's own first three octets.
            SendHelpers.device.pressDPadRight()
            Thread.sleep(400)
            SendHelpers.device.pressDPadCenter()
            val field = SendHelpers.device.wait(Until.findObject(By.textStartsWith("10.0.2.")), 10_000)
            Probe.record("address_field", field?.text)
            Probe.record("own_ipv4", AppGraph.network.ownIpv4)
            SendHelpers.settle()
            Shots.take("tv-enter-address")
            assertNotNull("address field prefilled with 10.0.2.", field)
            assertTrue(field.text.matches(Regex("""10\.0\.2\.\d*""")))
            SendHelpers.device.pressBack() // closes the on-screen keyboard if it opened
        }
    }
}
