package com.dugcanlift.coach.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.dugcanlift.coach.data.PlanPrintout
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Lays [PlanPrintout]'s lines out on US Letter pages and hands them to the
 * system print dialog, which offers every printer and "Save as PDF".
 *
 * Coach iOS writes the same PDF and opens the share sheet, whose Print and
 * Save to Files are always there. Android's share sheet has neither unless
 * some app happens to claim a PDF, while its print dialog always has both --
 * so this is the one place that reaches the same two outcomes on every
 * device. Text only, black on white whatever the app's appearance: it is for
 * paper. Nothing is recorded; a printout is not a send.
 */
object PlanPrintoutDocument {

    private fun paint(size: Float, typeface: Typeface, gray: Boolean = false) = TextPaint().apply {
        isAntiAlias = true
        textSize = size
        this.typeface = typeface
        color = if (gray) Color.DKGRAY else Color.BLACK
    }

    private val semibold: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun paintFor(style: PlanPrintout.Style): TextPaint = when (style) {
        PlanPrintout.Style.TITLE -> paint(20f, Typeface.DEFAULT_BOLD)
        PlanPrintout.Style.SUBTITLE -> paint(10f, Typeface.DEFAULT, gray = true)
        PlanPrintout.Style.DAY -> paint(14f, semibold)
        PlanPrintout.Style.WORKOUT -> paint(12f, semibold)
        PlanPrintout.Style.EXERCISE, PlanPrintout.Style.BODY -> paint(11f, Typeface.DEFAULT)
        PlanPrintout.Style.NOTE -> paint(10f, Typeface.DEFAULT, gray = true)
    }

    private fun indentFor(style: PlanPrintout.Style): Float = when (style) {
        PlanPrintout.Style.EXERCISE -> 12f
        PlanPrintout.Style.NOTE -> 24f
        else -> 0f
    }

    /** Writes the pages to [out]; returns how many there were. */
    fun write(lines: List<PlanPrintout.Line>, out: OutputStream): Int {
        val width = PlanPrintout.PAGE_WIDTH - PlanPrintout.MARGIN * 2
        val layouts = lines.map { line ->
            val textWidth = (width - indentFor(line.style)).toInt()
            StaticLayout.Builder.obtain(line.text, 0, line.text.length, paintFor(line.style), textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
        }
        val pages = PlanPrintout.paginate(
            heights = layouts.map { it.height.toFloat() },
            before = lines.map { it.before }
        )

        val document = PdfDocument()
        try {
            pages.forEachIndexed { number, indices ->
                val info = PdfDocument.PageInfo.Builder(
                    PlanPrintout.PAGE_WIDTH, PlanPrintout.PAGE_HEIGHT, number + 1
                ).create()
                val page = document.startPage(info)
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)
                var y = PlanPrintout.MARGIN
                indices.forEachIndexed { position, index ->
                    if (position > 0) y += lines[index].before
                    canvas.save()
                    canvas.translate(PlanPrintout.MARGIN + indentFor(lines[index].style), y)
                    layouts[index].draw(canvas)
                    canvas.restore()
                    y += layouts[index].height
                }
                document.finishPage(page)
            }
            document.writeTo(out)
        } finally {
            document.close()
        }
        return pages.size
    }

    /** Opens the system print dialog on this week, named for the file "Save as PDF" writes. */
    fun print(context: Context, fileName: String, lines: List<PlanPrintout.Line>) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.NA_LETTER)
            .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
            .build()
        manager.print(fileName.removeSuffix(".pdf"), Adapter(fileName, lines), attributes)
    }

    private class Adapter(
        private val fileName: String,
        private val lines: List<PlanPrintout.Line>
    ) : PrintDocumentAdapter() {

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            // The page is always US Letter, laid out once; a different paper
            // size is the print service's to scale to, as it would a PDF file.
            val info = PrintDocumentInfo.Builder(fileName)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .build()
            callback.onLayoutFinished(info, oldAttributes != newAttributes)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback
        ) {
            try {
                // Not closed here: the descriptor is the print framework's.
                FileOutputStream(destination.fileDescriptor).let { write(lines, it); it.flush() }
                if (cancellationSignal?.isCanceled == true) callback.onWriteCancelled()
                else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            }
        }
    }
}
