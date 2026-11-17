package app.ferry

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import app.ferry.core.HostPort
import app.ferry.net.BroadcastAddress
import app.ferry.net.ConnectionState
import app.ferry.probe.Probe
import app.ferry.probe.Shots
import org.junit.Assert.assertEquals
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
        TvHelpers.leaveTouchMode()
        AppGraph.prefs.manual = HostPort.parse("10.0.2.2:18799") // nothing listens there
        LaunchHelpers.launch().use { sc ->
            val vm = TvHelpers(sc).vm
            SendHelpers.waitUntil("PC not found", 60_000) { AppGraph.connection.state.value is ConnectionState.NotFound }
            SendHelpers.waitText(ctx.getString(R.string.tv_pc_not_found))
            SendHelpers.waitText(ctx.getString(R.string.try_again))
            SendHelpers.settle()
            Shots.take("tv-pc-not-found")

            // Try again has focus: one OK = one more locate.
            SendHelpers.waitUntil("Try again focused (focus=${vm.pcNotFoundFocus})", 10_000) { vm.pcNotFoundFocus == "try" }
            val before = locates()
            SendHelpers.device.pressDPadCenter()
            SendHelpers.waitUntil("located again", 30_000) {
                locates() > before && AppGraph.connection.state.value is ConnectionState.NotFound
            }
            Probe.record("locates", "$before → ${locates()}")
            SendHelpers.waitText(ctx.getString(R.string.enter_address))
            SendHelpers.waitUntil("Try again focused again (focus=${vm.pcNotFoundFocus})", 10_000) { vm.pcNotFoundFocus == "try" }

            // Enter address: number keyboard, prefilled with the TV's own first three octets.
            SendHelpers.device.pressDPadRight()
            SendHelpers.waitUntil("Enter address focused (focus=${vm.pcNotFoundFocus})", 5_000) { vm.pcNotFoundFocus == "enter" }
            SendHelpers.settle()
            Shots.take("tv-pc-not-found-enter-focused")
            SendHelpers.device.pressDPadCenter()
            val field = SendHelpers.device.wait(Until.findObject(By.textStartsWith("10.0.2.")), 10_000)
            Probe.record("address_field", field?.text)
            Probe.record("own_ipv4", AppGraph.network.ownIpv4)
            SendHelpers.settle()
            Shots.take("tv-enter-address")
            assertNotNull("address field prefilled with 10.0.2.", field)
            assertTrue(field.text.matches(Regex("""10\.0\.2\.\d*""")))

            // Audit O3: OK on "Enter address" put the focus in the field with the cursor after the prefill, so a
            // digit typed on the remote's keyboard lands at the end ("10.0.2." + "9" = "10.0.2.9", not "910.0.2.").
            SendHelpers.waitUntil("the field has the focus", 5_000) { vm.addressFieldFocused }
            val prefill = checkNotNull(vm.addressDraft)
            assertEquals(BroadcastAddress.firstThreeOctets(AppGraph.network.ownIpv4), prefill)
            SendHelpers.device.pressKeyCode(KeyEvent.KEYCODE_9)
            SendHelpers.waitUntil("typed digit arrives (draft=${vm.addressDraft})", 5_000) { vm.addressDraft != prefill }
            Probe.record("address_after_digit", vm.addressDraft)
            assertEquals("the digit goes after the prefilled part", prefill + "9", vm.addressDraft)
            assertNotNull(HostPort.parse(vm.addressDraft!!))
            SendHelpers.settle()
            Shots.take("tv-enter-address-typed")

            // Audit O2: this TV's address is on screen for the phone's "Enter the TV's address".
            val own = checkNotNull(AppGraph.network.ownIpv4)
            Probe.record("tv_address_on_screen", "own=$own uiautomator_sees=${SendHelpers.device.hasObject(By.textContains("address $own"))}")

            // Audit O16: the PC appears (here: its address becomes right) and nobody presses Try again; Ferry
            // looks again by itself every 10 s while "PC not found" is on screen.
            val locatesBefore = locates()
            AppGraph.prefs.manual = HostPort.parse(Probe.pc)
            SendHelpers.waitUntil("found without Try again", 45_000) { AppGraph.connection.state.value is ConnectionState.Connected }
            Probe.record("auto_retry", "connected by itself; locate lines $locatesBefore → ${locates()}")
        }
    }
}
