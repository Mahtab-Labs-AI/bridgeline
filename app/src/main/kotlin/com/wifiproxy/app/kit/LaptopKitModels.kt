package com.wifiproxy.app.kit

/** UI-facing progress of [LaptopKitBuilder.prepare] (design spec §8a / plan Task 7): not prepared,
 *  downloading N%, verifying, ready (with the copy-to-laptop instructions), or failed (with why). */
sealed interface KitProgress {
    data object NotPrepared : KitProgress
    data class Downloading(val percent: Int) : KitProgress
    data object Verifying : KitProgress
    data class Ready(val message: String) : KitProgress
    data class Failed(val reason: String) : KitProgress
}

/** The outcome [LaptopKitBuilder.prepare] hands back once it settles, independent of the last
 *  [KitProgress] the UI happened to observe. */
sealed interface KitResult {
    data class Success(val fileCount: Int) : KitResult
    data class Failure(val reason: String) : KitResult
}
