package com.windergoodlife.smsrelay.worker

/** Before Android 12 expedited work needs a separate foreground-worker implementation. */
internal fun supportsExpeditedUpload(sdkInt: Int): Boolean = sdkInt >= 31
