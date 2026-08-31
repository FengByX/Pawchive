package com.pawchive.core.util

/**
 * 内容更新通知跨模块约定（ARCH-FEATURE-003 系统通知推送）。
 *
 * data 模块的 Worker 发出通知（contentIntent 指向 app 模块的 MainActivity），
 * app 模块的 MainActivity 接收 extra 并跳转内容更新页；双方通过本对象对齐
 * Activity 类名与 Intent extra 键，避免字符串硬编码漂移。
 */
object ContentUpdateConstants {

    /** 通知 contentIntent 的跳转目标（MainActivity 完整类名）。 */
    const val ACTIVITY_CLASS_NAME = "com.pawchive.ui.MainActivity"

    /** 通知点击后打开内容更新页的 Intent extra 键。 */
    const val EXTRA_OPEN_CONTENT_UPDATES = "pawchive.extra.OPEN_CONTENT_UPDATES"
}
