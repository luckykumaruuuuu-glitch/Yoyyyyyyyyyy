package io.github.gallo.sonycamera.usb

/**
 * Pure USB interface check shared by device discovery and session opening.
 * Android USB class/direction/type constants are represented by their USB
 * descriptor values so this predicate can be tested without camera hardware.
 */
object UsbPtpInterfaceMatcher {
    data class EndpointDescriptor(val type: Int, val direction: Int)

    fun isCompatible(interfaceClass: Int, endpoints: Collection<EndpointDescriptor>): Boolean {
        if (interfaceClass != USB_CLASS_PTP && interfaceClass != USB_CLASS_VENDOR_SPECIFIC) {
            return false
        }
        val hasBulkIn = endpoints.any { it.type == USB_ENDPOINT_BULK && it.direction == USB_DIR_IN }
        val hasBulkOut = endpoints.any { it.type == USB_ENDPOINT_BULK && it.direction == USB_DIR_OUT }
        return hasBulkIn && hasBulkOut
    }

    private const val USB_CLASS_PTP = 6
    private const val USB_CLASS_VENDOR_SPECIFIC = 255
    private const val USB_ENDPOINT_BULK = 2
    private const val USB_DIR_OUT = 0
    private const val USB_DIR_IN = 0x80
}
