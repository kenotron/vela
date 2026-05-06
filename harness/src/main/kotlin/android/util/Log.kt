package android.util

    object Log {
        @JvmStatic fun d(tag: String, msg: String) = Unit
        @JvmStatic fun d(tag: String, msg: String, tr: Throwable?) = Unit
        @JvmStatic fun v(tag: String, msg: String) = Unit
        @JvmStatic fun i(tag: String, msg: String) = Unit
        @JvmStatic fun w(tag: String, msg: String) = Unit
        @JvmStatic fun w(tag: String, msg: String, tr: Throwable?) = Unit
        @JvmStatic fun e(tag: String, msg: String) = System.err.println("[$tag] $msg")
        @JvmStatic fun e(tag: String, msg: String, tr: Throwable?) = System.err.println("[$tag] $msg  ${tr?.message}")
    }
    