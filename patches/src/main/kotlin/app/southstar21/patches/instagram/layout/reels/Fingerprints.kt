package app.southstar21.patches.instagram.layout.reels

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val EXTENSION_CLASS =
    "Lapp/southstar21/extension/instagram/reels/DisableReelsPatch;"

private const val MEDIA_CLASS = "Lcom/instagram/feed/media/Media;"
private const val VIEW_PAGER_2_CLASS = "Landroidx/viewpager2/widget/ViewPager2;"

// region Home feed

/**
 * FeedItem's getter for its type (MEDIA, AD, CLIPS_NETEGO, ...), which names the missing
 * field when it throws. FeedItem is the class with a ClipsNetego field ("Suggested reels").
 */
internal object FeedItemTypeFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf(),
    strings = listOf("feedItemType"),
    custom = { _, classDef ->
        classDef.fields.any { it.type == "Lcom/instagram/api/schemas/ClipsNetego;" }
    },
)

/**
 * FeedItem's null-safe getter for its media, the one that unwraps the content holder:
 * `public final ()Media`, which gets the media through a static call.
 *
 * Looked up in FeedItem's methods directly rather than with a class fingerprint. Hide ads and
 * this patch change FeedItem, and Morphe Patcher 2.3.0 doesn't match a class fingerprint's
 * method in a class that was already changed.
 */
internal fun feedItemMediaGetter(): Method {
    val getters = FeedItemTypeFingerprint.originalClassDef.methods.filter { method ->
        method.accessFlags == (AccessFlags.PUBLIC.value or AccessFlags.FINAL.value) &&
                method.returnType == MEDIA_CLASS &&
                method.parameterTypes.isEmpty() &&
                method.implementation?.instructions?.any { instruction ->
                    instruction.opcode == Opcode.INVOKE_STATIC &&
                            ((instruction as? ReferenceInstruction)?.reference as? MethodReference)
                                ?.returnType == MEDIA_CLASS
                } == true
    }
    return getters.singleOrNull()
        ?: throw PatchException("Expected one media getter in FeedItem, found ${getters.size}")
}

/** Parses one FeedItem from JSON. Every feed, and the feed cache, goes through it. */
internal object ParseFeedItemFingerprint : Fingerprint(
    classFingerprint = FeedItemTypeFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(name = "parseFromJsonParser"),
    ),
)

/** Media's ProductType (FEED, CAROUSEL_CONTAINER, CLIPS, ...). */
internal object MediaProductTypeFingerprint : Fingerprint(
    definingClass = "Lcom/instagram/feed/media/MediaExtKt;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "Lcom/instagram/model/mediatype/ProductType;",
    parameters = listOf(MEDIA_CLASS),
)

// endregion

/** Parses one section ("DiscoverySection") of the Explore grid. */
internal object ParseExploreSectionFingerprint : Fingerprint(
    name = "unsafeParseFromJson",
    returnType = "Ljava/lang/Object;",
    strings = listOf("DiscoverySection", "explore_item_info"),
)

// region Player

internal object ClipsViewerFragmentOnViewCreatedFingerprint : Fingerprint(
    classFingerprint = Fingerprint(
        strings = listOf("ClipsViewerFragment.onCreate:clipsViewerViewPager"),
    ),
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "Landroid/os/Bundle;"),
)

internal object ViewPager2SetAdapterFingerprint : Fingerprint(
    definingClass = VIEW_PAGER_2_CLASS,
    name = "setAdapter",
    returnType = "V",
    parameters = listOf("L"),
)

internal object ViewPager2SetUserInputEnabledFingerprint : Fingerprint(
    definingClass = VIEW_PAGER_2_CLASS,
    name = "setUserInputEnabled",
    returnType = "V",
    parameters = listOf("Z"),
)

// endregion
