package eu.kanade.tachiyomi.util.chapter

import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date
import java.util.TreeSet

/**
 * Helper method for syncing the list of chapters from the source with the ones from the database.
 *
 * @param db the database.
 * @param rawSourceChapters a list of chapters from the source.
 * @param manga the manga of the chapters.
 * @param source the source of the chapters.
 * @return a pair of new insertions and deletions.
 */
fun syncChaptersWithSource(
    db: DatabaseHelper,
    rawSourceChapters: List<SChapter>,
    manga: Manga,
    source: Source,
): Pair<List<Chapter>, List<Chapter>> {
    if (rawSourceChapters.isEmpty()) {
        throw NoChaptersException()
    }

    val downloadManager: DownloadManager = Injekt.get()

    // Chapters from db.
    val dbChapters = db.getChapters(manga).executeAsBlocking()

    val sourceChapters = rawSourceChapters
        .distinctBy { it.url }
        .mapIndexed { i, sChapter ->
            Chapter.create().apply {
                copyFrom(sChapter)
                manga_id = manga.id
                source_order = i
            }
        }

    // Chapters from the source not in db.
    val toAdd = mutableListOf<Chapter>()

    // Chapters whose metadata have changed.
    val toChange = mutableListOf<Chapter>()

    // Chapters from the db not in source.
    val toDelete = dbChapters.filterNot { dbChapter ->
        sourceChapters.any { sourceChapter ->
            dbChapter.url == sourceChapter.url
        }
    }

    for (sourceChapter in sourceChapters) {
        // This forces metadata update for the main viewable things in the chapter list.
        if (source is HttpSource) {
            source.prepareNewChapter(sourceChapter, manga)
        }
        // Recognize chapter number for the chapter.
        ChapterRecognition.parseChapterNumber(sourceChapter, manga)

        val dbChapter = dbChapters.find { it.url == sourceChapter.url }

        // Add the chapter if not in db already, or update if the metadata changed.
        if (dbChapter == null) {
            if (sourceChapter.date_upload == 0L) {
                sourceChapter.date_upload = Date().time
            }
            toAdd.add(sourceChapter)
        } else {
            if (shouldUpdateDbChapter(dbChapter, sourceChapter)) {
                if (dbChapter.name != sourceChapter.name && downloadManager.isChapterDownloaded(dbChapter, manga)) {
                    downloadManager.renameChapter(source, manga, dbChapter, sourceChapter)
                }
                dbChapter.scanlator = sourceChapter.scanlator
                dbChapter.name = sourceChapter.name
                dbChapter.chapter_number = sourceChapter.chapter_number
                dbChapter.source_order = sourceChapter.source_order
                if (sourceChapter.date_upload != 0L) {
                    dbChapter.date_upload = sourceChapter.date_upload
                }
                toChange.add(dbChapter)
            }
        }
    }

    // Return if there's nothing to add, delete or change, avoiding unnecessary db transactions.
    if (toAdd.isEmpty() && toDelete.isEmpty() && toChange.isEmpty()) {
        return Pair(emptyList(), emptyList())
    }

    val readded = mutableListOf<Chapter>()

    db.inTransaction {
        val deletedChapterNumbers = TreeSet<Float>()
        val deletedReadChapterNumbers = TreeSet<Float>()

        if (toDelete.isNotEmpty()) {
            for (chapter in toDelete) {
                if (chapter.read) {
                    deletedReadChapterNumbers.add(chapter.chapter_number)
                }
                deletedChapterNumbers.add(chapter.chapter_number)
            }
            db.deleteChapters(toDelete).executeAsBlocking()
        }

        if (toAdd.isNotEmpty()) {
            // Set the date fetch for new items in reverse order to allow another sorting method.
            // Sources MUST return the chapters from most to less recent, which is common.
            var now = Date().time

            for (i in toAdd.indices.reversed()) {
                val chapter = toAdd[i]
                chapter.date_fetch = now++

                if (chapter.isRecognizedNumber && chapter.chapter_number in deletedChapterNumbers) {
                    // Try to mark already read chapters as read when the source deletes them
                    if (chapter.chapter_number in deletedReadChapterNumbers) {
                        chapter.read = true
                    }
                    // Try to to use the fetch date it originally had to not pollute 'Updates' tab
                    toDelete.filter { it.chapter_number == chapter.chapter_number }
                        .minByOrNull { it.date_fetch }!!.let {
                        chapter.date_fetch = it.date_fetch
                    }
                    readded.add(chapter)
                }
            }
            val chapters = db.insertChapters(toAdd).executeAsBlocking()
            toAdd.forEach { chapter ->
                chapter.id = chapters.results().getValue(chapter).insertedId()
            }
        }

        if (toChange.isNotEmpty()) {
            db.insertChapters(toChange).executeAsBlocking()
        }

        // Fix order in source.
        db.fixChaptersSourceOrder(sourceChapters).executeAsBlocking()

        // Set this manga as updated since chapters were changed
        // Note that last_update actually represents last time the chapter list changed at all
        manga.last_update = Date().time
        db.updateLastUpdated(manga).executeAsBlocking()
    }

    return Pair(toAdd.subtract(readded).toList(), toDelete.subtract(readded).toList())
}

private fun shouldUpdateDbChapter(dbChapter: Chapter, sourceChapter: Chapter): Boolean {
    return dbChapter.scanlator != sourceChapter.scanlator || dbChapter.name != sourceChapter.name ||
        dbChapter.date_upload != sourceChapter.date_upload ||
        dbChapter.chapter_number != sourceChapter.chapter_number ||
        dbChapter.source_order != sourceChapter.source_order
}

class NoChaptersException : Exception()
