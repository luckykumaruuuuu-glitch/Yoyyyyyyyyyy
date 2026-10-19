package com.wedfaces.cameraoperator.camera

import io.github.gallo.sonycamera.usb.UsbPtpInterfaceMatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPtpInterfaceMatcherTest {
    private val bulkIn = UsbPtpInterfaceMatcher.EndpointDescriptor(type = 2, direction = 0x80)
    private val bulkOut = UsbPtpInterfaceMatcher.EndpointDescriptor(type = 2, direction = 0)
    private val interruptIn = UsbPtpInterfaceMatcher.EndpointDescriptor(type = 3, direction = 0x80)

    @Test
    fun acceptsPtpAndVendorSpecificInterfacesOnlyWithBothBulkDirections() {
        assertTrue(UsbPtpInterfaceMatcher.isCompatible(6, listOf(bulkIn, bulkOut, interruptIn)))
        assertTrue(UsbPtpInterfaceMatcher.isCompatible(255, listOf(bulkIn, bulkOut)))
        assertFalse(UsbPtpInterfaceMatcher.isCompatible(6, listOf(bulkIn)))
        assertFalse(UsbPtpInterfaceMatcher.isCompatible(8, listOf(bulkIn, bulkOut)))
        assertFalse(UsbPtpInterfaceMatcher.isCompatible(255, listOf(interruptIn, bulkOut)))
    }
}
