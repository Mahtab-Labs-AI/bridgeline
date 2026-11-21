package com.wifiproxy.app.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** User decision D7: while USB sharing is on the laptop always keeps a seat, so every saved Wi-Fi
 *  device (active or inactive) plus that seat must fit in maxClients. */
class SeatRulesTest {
    @Test
    fun `usb on reserves one seat for the laptop`() {
        assertTrue(SeatRules.fits(savedWifiSlots = 2, usbEnabled = true, maxClients = 3))
        assertFalse(SeatRules.fits(savedWifiSlots = 3, usbEnabled = true, maxClients = 3))
        assertTrue(SeatRules.fits(savedWifiSlots = 0, usbEnabled = true, maxClients = 1))
        assertFalse(SeatRules.fits(savedWifiSlots = 1, usbEnabled = true, maxClients = 1))
    }

    @Test
    fun `usb off lets wifi devices use every seat`() {
        assertTrue(SeatRules.fits(savedWifiSlots = 3, usbEnabled = false, maxClients = 3))
        assertFalse(SeatRules.fits(savedWifiSlots = 3, usbEnabled = false, maxClients = 2))
        assertTrue(SeatRules.fits(savedWifiSlots = 0, usbEnabled = false, maxClients = 1))
    }

    @Test
    fun `usb can be turned on only while a seat is left for it`() {
        assertTrue(SeatRules.canEnableUsb(savedWifiSlots = 2, maxClients = 3))
        assertFalse(SeatRules.canEnableUsb(savedWifiSlots = 3, maxClients = 3))
        assertFalse(SeatRules.canEnableUsb(savedWifiSlots = 1, maxClients = 1))
    }

    @Test
    fun `a save that changes the password counts no saved devices`() {
        // Changing the Wi-Fi password deletes every saved device (plan decision 5).
        assertTrue(SeatRules.fitsAfterSave(savedWifiSlots = 2, passwordChanged = true, usbEnabled = true, maxClients = 1))
        assertFalse(SeatRules.fitsAfterSave(savedWifiSlots = 2, passwordChanged = false, usbEnabled = true, maxClients = 2))
        assertTrue(SeatRules.fitsAfterSave(savedWifiSlots = 2, passwordChanged = false, usbEnabled = false, maxClients = 2))
    }

    @Test
    fun `a name-only change keeps the saved devices, so they all still count`() {
        // A rename isn't a password change: passwordChanged = false, and the D7 rule counts every device.
        assertFalse(SeatRules.fitsAfterSave(savedWifiSlots = 1, passwordChanged = false, usbEnabled = true, maxClients = 1))
        assertTrue(SeatRules.fitsAfterSave(savedWifiSlots = 1, passwordChanged = false, usbEnabled = true, maxClients = 2))
    }
}
