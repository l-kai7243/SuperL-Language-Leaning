package com.example.myapplication111.util

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 读取用户选择的 txt / pdf 文件为纯文本。 */
object FileReader {

    fun readFileContent(context: Context, uri: Uri): String? {
        val mimeType = context.contentResolver.getType(uri)
        return if (mimeType == "application/pdf" || uri.toString().endsWith(".pdf")) {
            readPdfFromUri(context, uri)
        } else {
            readTextFromUri(context, uri)
        }
    }

    private fun readTextFromUri(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun readPdfFromUri(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                // PDDocument 实现 Closeable：用 use 保证解析失败时也不会泄漏句柄
                PDDocument.load(inputStream).use { document ->
                    PDFTextStripper().getText(document)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

fun formatTimestamp(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

