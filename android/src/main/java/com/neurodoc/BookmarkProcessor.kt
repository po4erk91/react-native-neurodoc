package com.neurodoc

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableNativeArray
import com.facebook.react.bridge.WritableNativeMap
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.File
import java.util.UUID

object BookmarkProcessor {

    fun getBookmarks(pdfUrl: String, promise: Promise) {
        val file = resolveFile(pdfUrl)
        val doc = PDDocument.load(file)

        val bookmarks = WritableNativeArray()
        val outline = doc.documentCatalog.documentOutline

        if (outline != null) {
            flattenOutline(outline, 0, bookmarks, doc)
        }

        doc.close()

        promise.resolve(WritableNativeMap().apply {
            putArray("bookmarks", bookmarks)
        })
    }

    fun addBookmarks(pdfUrl: String, bookmarksArray: ReadableArray, tempDir: File, promise: Promise) {
        val file = resolveFile(pdfUrl)
        val doc = PDDocument.load(file)

        var outline = doc.documentCatalog.documentOutline
        if (outline == null) {
            outline = PDDocumentOutline()
            doc.documentCatalog.documentOutline = outline
        }

        // Build flat list of existing nodes for parentIndex resolution
        val existingNodes = mutableListOf<PDOutlineItem>()
        flattenOutlineNodes(outline, existingNodes)

        for (i in 0 until bookmarksArray.size()) {
            val bm = bookmarksArray.getMap(i) ?: continue
            val title = bm.getString("title") ?: continue
            val pageIndex = bm.getInt("pageIndex")

            if (pageIndex < 0 || pageIndex >= doc.numberOfPages) continue

            val page = doc.getPage(pageIndex)
            val dest = PDPageFitWidthDestination()
            dest.page = page

            val item = PDOutlineItem()
            item.title = title
            item.destination = dest

            val hasParent = bm.hasKey("parentIndex") && !bm.isNull("parentIndex")
            if (hasParent) {
                val parentIndex = bm.getInt("parentIndex")
                if (parentIndex in existingNodes.indices) {
                    existingNodes[parentIndex].addLast(item)
                } else {
                    outline.addLast(item)
                }
            } else {
                outline.addLast(item)
            }

            existingNodes.add(item)
        }

        val outputFile = File(tempDir, "${UUID.randomUUID()}.pdf")
        doc.save(outputFile)
        doc.close()

        promise.resolve(WritableNativeMap().apply {
            putString("pdfUrl", "file://${outputFile.absolutePath}")
        })
    }

    fun removeBookmarks(pdfUrl: String, indexesArray: ReadableArray, tempDir: File, promise: Promise) {
        val file = resolveFile(pdfUrl)
        val doc = PDDocument.load(file)

        val outline = doc.documentCatalog.documentOutline
        if (outline != null) {
            val nodes = mutableListOf<PDOutlineItem>()
            flattenOutlineNodes(outline, nodes)

            val indexes = (0 until indexesArray.size()).map { indexesArray.getInt(it) }.toSet()

            // Remove in reverse order to preserve indexes
            for (idx in indexes.sortedDescending()) {
                if (idx in nodes.indices) {
                    removeOutlineItem(nodes[idx])
                }
            }
        }

        val outputFile = File(tempDir, "${UUID.randomUUID()}.pdf")
        doc.save(outputFile)
        doc.close()

        promise.resolve(WritableNativeMap().apply {
            putString("pdfUrl", "file://${outputFile.absolutePath}")
        })
    }

    // MARK: - Helpers

    private fun resolveFile(urlString: String): File {
        val path = if (urlString.startsWith("file://")) {
            urlString.removePrefix("file://")
        } else {
            urlString
        }
        return File(path)
    }

    private fun flattenOutline(outline: PDDocumentOutline, level: Int, result: WritableNativeArray, doc: PDDocument) {
        var item = outline.firstChild
        while (item != null) {
            val childCount = countChildren(item)
            val pageIndex = getPageIndex(item, doc)

            result.pushMap(WritableNativeMap().apply {
                putString("title", item.title ?: "")
                putInt("pageIndex", pageIndex)
                putInt("level", level)
                putInt("children", childCount)
            })

            if (childCount > 0) {
                flattenOutlineChildren(item, level + 1, result, doc)
            }

            item = item.nextSibling
        }
    }

    private fun flattenOutlineChildren(parent: PDOutlineItem, level: Int, result: WritableNativeArray, doc: PDDocument) {
        var item = parent.firstChild
        while (item != null) {
            val childCount = countChildren(item)
            val pageIndex = getPageIndex(item, doc)

            result.pushMap(WritableNativeMap().apply {
                putString("title", item.title ?: "")
                putInt("pageIndex", pageIndex)
                putInt("level", level)
                putInt("children", childCount)
            })

            if (childCount > 0) {
                flattenOutlineChildren(item, level + 1, result, doc)
            }

            item = item.nextSibling
        }
    }

    private fun flattenOutlineNodes(outline: PDDocumentOutline, nodes: MutableList<PDOutlineItem>) {
        var item = outline.firstChild
        while (item != null) {
            nodes.add(item)
            flattenOutlineItemNodes(item, nodes)
            item = item.nextSibling
        }
    }

    private fun flattenOutlineItemNodes(parent: PDOutlineItem, nodes: MutableList<PDOutlineItem>) {
        var item = parent.firstChild
        while (item != null) {
            nodes.add(item)
            flattenOutlineItemNodes(item, nodes)
            item = item.nextSibling
        }
    }

    private fun countChildren(item: PDOutlineItem): Int {
        var count = 0
        var child = item.firstChild
        while (child != null) {
            count++
            child = child.nextSibling
        }
        return count
    }

    private fun getPageIndex(item: PDOutlineItem, doc: PDDocument): Int {
        return try {
            val dest = item.destination
            if (dest is PDPageDestination) {
                val page = dest.page
                if (page != null) {
                    doc.pages.indexOf(page)
                } else {
                    dest.pageNumber
                }
            } else {
                // Try to resolve action-based destinations
                val action = item.action
                if (action != null) {
                    val d = action.cosObject.getDictionaryObject(COSName.D)
                    if (d is com.tom_roush.pdfbox.cos.COSArray && d.size() > 0) {
                        val pageRef = d.getObject(0)
                        for (i in 0 until doc.numberOfPages) {
                            if (doc.getPage(i).cosObject === pageRef) return i
                        }
                    }
                }
                -1
            }
        } catch (e: Exception) {
            -1
        }
    }

    private fun removeOutlineItem(item: PDOutlineItem) {
        // PDFBox outline items are a linked list stored in COSDictionary
        // We manipulate Prev/Next/First/Last/Parent pointers via COS layer
        val cosDict = item.cosObject
        val parentRef = cosDict.getDictionaryObject(COSName.PARENT) ?: return
        val parentDict = parentRef as? COSDictionary ?: return

        val prevItem = cosDict.getDictionaryObject(COSName.getPDFName("Prev"))
        val nextItem = cosDict.getDictionaryObject(COSName.getPDFName("Next"))

        if (prevItem != null) {
            val prevDict = prevItem as? COSDictionary ?: return
            if (nextItem != null) {
                prevDict.setItem(COSName.getPDFName("Next"), nextItem)
            } else {
                prevDict.removeItem(COSName.getPDFName("Next"))
                parentDict.setItem(COSName.getPDFName("Last"), prevItem)
            }
        }

        if (nextItem != null) {
            val nextDict = nextItem as? COSDictionary ?: return
            if (prevItem != null) {
                nextDict.setItem(COSName.getPDFName("Prev"), prevItem)
            } else {
                nextDict.removeItem(COSName.getPDFName("Prev"))
                parentDict.setItem(COSName.getPDFName("First"), nextItem)
            }
        }

        // If this was the only child
        if (prevItem == null && nextItem == null) {
            parentDict.removeItem(COSName.getPDFName("First"))
            parentDict.removeItem(COSName.getPDFName("Last"))
        }

        // Update Count
        val count = parentDict.getInt(COSName.getPDFName("Count"), 0)
        if (count != 0) {
            val newCount = if (count > 0) count - 1 else count + 1
            parentDict.setInt(COSName.getPDFName("Count"), newCount)
        }
    }
}
