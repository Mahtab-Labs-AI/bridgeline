package com.wifiproxy.app.session

/**
 * User decision D7: while USB sharing is on, the USB laptop always keeps a seat. Saved Wi-Fi
 * devices that don't fit the rest stay saved but inactive for a session, so settings must never
 * be changed into a state where the saved devices plus that seat exceed `maxClients`.
 */
internal object SeatRules {
    fun fits(savedWifiSlots: Int, usbEnabled: Boolean, maxClients: Int): Boolean =
        savedWifiSlots + (if (usbEnabled) 1 else 0) <= maxClients

    fun canEnableUsb(savedWifiSlots: Int, maxClients: Int): Boolean =
        fits(savedWifiSlots, usbEnabled = true, maxClients = maxClients)

    /** [fits] for a settings save: one that changes the Wi-Fi password deletes every saved device
     *  (plan decision 5), so none of them count against the new `maxClients`. A name-only change
     *  keeps them, so they all still count. */
    fun fitsAfterSave(savedWifiSlots: Int, passwordChanged: Boolean, usbEnabled: Boolean, maxClients: Int): Boolean =
        fits(if (passwordChanged) 0 else savedWifiSlots, usbEnabled, maxClients)
}
