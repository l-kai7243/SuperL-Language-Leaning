package com.example.myapplication111.util

import java.util.UUID

/** 收藏/生词共用的唯一 id：时间戳 + 短随机段，文件名与日志里都好认。 */
fun newId(): String = System.currentTimeMillis().toString() + "_" + UUID.randomUUID().toString().take(8)
