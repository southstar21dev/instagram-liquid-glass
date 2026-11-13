package app.southstar21.patches.instagram.layout.consciousgate

import app.morphe.patcher.patch.bytecodePatch
import app.southstar21.patches.instagram.misc.extension.markPatchIncluded
import app.southstar21.patches.instagram.misc.extension.sharedExtensionPatch
import app.southstar21.patches.instagram.shared.Constants.COMPATIBILITY_INSTAGRAM

private const val EXTENSION_CLASS =
    "Lapp/southstar21/extension/instagram/consciousgate/ConsciousGatePatch;"

@Suppress("unused")
val consciousGatePatch = bytecodePatch(
    name = "Conscious gate",
    description = "Gives the home feed, Reels and Explore a daily time budget. Near the end they slowly frost over and can only be scrolled, " +
            "then a gate closes them until the next day. Searching for people, messages and profiles keep working.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    // The extension finds the home feed by its class name and runs from the shared
    // main activity hook, so no other hooks are needed.
    dependsOn(sharedExtensionPatch)

    execute {
        markPatchIncluded(EXTENSION_CLASS)
    }
}
