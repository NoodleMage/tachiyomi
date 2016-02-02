package eu.kanade.tachiyomi.data.backup;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.pushtorefresh.storio.sqlite.operations.put.PutResult;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import eu.kanade.tachiyomi.data.database.DatabaseHelper;
import eu.kanade.tachiyomi.data.database.models.Category;
import eu.kanade.tachiyomi.data.database.models.Chapter;
import eu.kanade.tachiyomi.data.database.models.Manga;
import eu.kanade.tachiyomi.data.database.models.MangaCategory;
import eu.kanade.tachiyomi.data.database.models.MangaSync;

/**
 * File format:
 *
 * {
 *     "mangas": [
 *         {
 *             "manga": {"id": 1, ...},
 *             "chapters": [{"id": 1, ...}],
 *             "sync": [{"id": 1, ...}],
 *             "categories": ["cat1", "cat2"]
 *         },
 *         { ... }
 *     ],
 *     "categories": [
 *         {"id": 1, ...},
 *         {"id": 2, ...}
 *     ]
 * }
 */
public class BackupManager {

    private final DatabaseHelper db;
    private final Gson gson;

    private static final String MANGA = "manga";
    private static final String MANGAS = "mangas";
    private static final String CHAPTERS = "chapters";
    private static final String MANGA_SYNC = "sync";
    private static final String CATEGORIES = "categories";

    public BackupManager(DatabaseHelper db) {
        this.db = db;
        gson = new Gson();
    }

    public void backupToFile(File backupFile) throws IOException {
        final JsonObject root = backupToJson();

        FileWriter writer = null;
        try {
            writer = new FileWriter(backupFile);
            gson.toJson(root, writer);
        } finally {
            if (writer != null)
                writer.close();
        }
    }

    public JsonObject backupToJson() {
        final JsonObject root = new JsonObject();

        final JsonArray mangaEntries = new JsonArray();
        root.add(MANGAS, mangaEntries);
        for (Manga manga : db.getFavoriteMangas().executeAsBlocking()) {
            backupManga(manga, mangaEntries);
        }

        final JsonArray categoryEntries = new JsonArray();
        root.add(CATEGORIES, categoryEntries);
        for (Category category : db.getCategories().executeAsBlocking()) {
            backupCategory(category, categoryEntries);
        }

        return root;
    }

    private void backupManga(Manga manga, JsonArray entries) {
        // Entry for this manga
        JsonObject entry = new JsonObject();

        // Backup manga fields
        entry.add(MANGA, gson.toJsonTree(manga));

        // Backup all the chapters
        List<Chapter> chapters = db.getChapters(manga).executeAsBlocking();
        if (!chapters.isEmpty()) {
            entry.add(CHAPTERS, gson.toJsonTree(chapters));
        }

        // Backup manga sync
        List<MangaSync> mangaSync = db.getMangasSync(manga).executeAsBlocking();
        if (!mangaSync.isEmpty()) {
            entry.add(MANGA_SYNC, gson.toJsonTree(mangaSync));
        }

        // Backup categories for this manga
        List<Category> categoriesForManga = db.getCategoriesForManga(manga).executeAsBlocking();
        if (!categoriesForManga.isEmpty()) {
            List<String> categoriesNames = new ArrayList<>();
            for (Category category : categoriesForManga) {
                categoriesNames.add(category.name);
            }
            entry.add(CATEGORIES, gson.toJsonTree(categoriesNames));
        }

        // Finally add it to the manga list
        entries.add(entry);
    }

    private void backupCategory(Category category, JsonArray entries) {
        entries.add(gson.toJsonTree(category));
    }

    public void restoreFromFile(File restoreFile) throws IOException {
        JsonReader reader = null;
        try {
            reader = new JsonReader(new FileReader(restoreFile));
            JsonObject root = new JsonParser().parse(reader).getAsJsonObject();
            restoreFromJson(root);
        } finally {
            if (reader != null)
                reader.close();
        }
    }

    public void restoreFromJson(JsonObject root) {
        try {
            db.lowLevel().beginTransaction();
            restoreCategories(root);
            restoreMangas(root);
            db.lowLevel().setTransactionSuccessful();
        } finally {
            db.lowLevel().endTransaction();
        }
    }

    private void restoreCategories(JsonObject root) {
        // Get categories from file and from db
        List<Category> dbCategories = db.getCategories().executeAsBlocking();
        List<Category> backupCategories = getArrayOrEmpty(root.get(CATEGORIES),
                new TypeToken<List<Category>>() {}.getType());

        // Iterate over them
        for (Category category : backupCategories) {
            // Used to know if the category is already in the db
            boolean found = false;
            for (Category dbCategory : dbCategories) {
                // If the category is already in the db, assign the id to the file's category
                // and do nothing
                if (category.getNameLower().equals(dbCategory.getNameLower())) {
                    category.id = dbCategory.id;
                    found = true;
                    break;
                }
            }
            // If the category isn't in the db, remove the id and insert a new category
            // Store the inserted id in the category
            if (!found) {
                // Let the db assign the id
                category.id = null;
                PutResult result = db.insertCategory(category).executeAsBlocking();
                category.id = result.insertedId().intValue();
            }
        }
    }

    private void restoreMangas(JsonObject root) {
        JsonArray backupMangas = gson.fromJson(root.get(MANGAS), JsonArray.class);
        if (backupMangas == null)
            return;

        Type chapterToken = new TypeToken<List<Chapter>>(){}.getType();
        Type mangaSyncToken = new TypeToken<List<MangaSync>>(){}.getType();
        Type categoriesNamesToken = new TypeToken<List<String>>(){}.getType();

        for (JsonElement backupManga : backupMangas) {
            // Map every entry to objects
            JsonObject element = backupManga.getAsJsonObject();
            Manga manga = gson.fromJson(element.get(MANGA), Manga.class);
            List<Chapter> chapters = getArrayOrEmpty(element.get(CHAPTERS), chapterToken);
            List<MangaSync> mangasSync = getArrayOrEmpty(element.get(MANGA_SYNC), mangaSyncToken);
            List<String> categories = getArrayOrEmpty(element.get(CATEGORIES), categoriesNamesToken);

            restoreManga(manga, chapters, mangasSync, categories);
            restoreChaptersForManga(manga, chapters);
            restoreSyncForManga(manga, mangasSync);
            restoreCategoriesForManga(manga, categories);
        }
    }

    private void restoreManga(Manga manga, List<Chapter> chapters,
                              List<MangaSync> mangasSync, List<String> categories) {

        // Try to find existing manga in db
        Manga dbManga = db.getManga(manga.url, manga.source).executeAsBlocking();
        if (dbManga == null) {
            // Let the db assign the id
            manga.id = null;
            PutResult result = db.insertManga(manga).executeAsBlocking();
            manga.id = result.insertedId();
        } else {
            // If it exists already, we copy only the values related to the source from the db
            // (they can be up to date). Local values (flags) are kept from the backup.
            manga.id = dbManga.id;
            manga.copyFrom(dbManga);
            manga.favorite = true;
            db.insertManga(manga).executeAsBlocking();
        }

        // Fix foreign keys with the current manga id
        for (Chapter chapter : chapters) {
            chapter.manga_id = manga.id;
        }
        for (MangaSync mangaSync : mangasSync) {
            mangaSync.manga_id = manga.id;
        }
    }

    private void restoreChaptersForManga(Manga manga, List<Chapter> chapters) {
        List<Chapter> dbChapters = db.getChapters(manga).executeAsBlocking();
        List<Chapter> chaptersToUpdate = new ArrayList<>();
        for (Chapter backupChapter : chapters) {
            // Try to find existing chapter in db
            int pos = dbChapters.indexOf(backupChapter);
            if (pos != -1) {
                Chapter dbChapter = dbChapters.get(pos);
                // If one of them was read, the chapter will be marked as read
                dbChapter.read = backupChapter.read || dbChapter.read;
                dbChapter.last_page_read = Math.max(backupChapter.last_page_read, dbChapter.last_page_read);
                chaptersToUpdate.add(dbChapter);
            } else {
                // Insert new chapter. Let the db assign the id
                backupChapter.id = null;
                chaptersToUpdate.add(backupChapter);
            }
        }

        if (!chaptersToUpdate.isEmpty()) {
            db.insertChapters(chaptersToUpdate).executeAsBlocking();
        }
    }

    private void restoreCategoriesForManga(Manga manga, List<String> categories) {
        List<Category> dbCategories = db.getCategories().executeAsBlocking();
        List<MangaCategory> mangaCategoriesToUpdate = new ArrayList<>();
        for (String backupCategoryStr : categories) {
            for (Category dbCategory : dbCategories) {
                if (backupCategoryStr.toLowerCase().equals(dbCategory.getNameLower())) {
                    mangaCategoriesToUpdate.add(MangaCategory.create(manga, dbCategory));
                    break;
                }
            }
        }

        if (!mangaCategoriesToUpdate.isEmpty()) {
            List<Manga> mangaAsList = new ArrayList<>();
            mangaAsList.add(manga);
            db.deleteOldMangasCategories(mangaAsList).executeAsBlocking();
            db.insertMangasCategories(mangaCategoriesToUpdate).executeAsBlocking();
        }
    }

    private void restoreSyncForManga(Manga manga, List<MangaSync> mangasSync) {
        List<MangaSync> dbSyncs = db.getMangasSync(manga).executeAsBlocking();
        List<MangaSync> syncToUpdate = new ArrayList<>();
        for (MangaSync backupSync : mangasSync) {
            // Try to find existing chapter in db
            int pos = dbSyncs.indexOf(backupSync);
            if (pos != -1) {
                MangaSync dbSync = dbSyncs.get(pos);
                // Mark the max chapter as read and nothing else
                dbSync.last_chapter_read = Math.max(backupSync.last_chapter_read, dbSync.last_chapter_read);
                syncToUpdate.add(dbSync);
            } else {
                // Insert new sync. Let the db assign the id
                backupSync.id = null;
                syncToUpdate.add(backupSync);
            }
        }

        if (!syncToUpdate.isEmpty()) {
            db.insertMangasSync(syncToUpdate).executeAsBlocking();
        }
    }

    private <T> List<T> getArrayOrEmpty(JsonElement element, Type type) {
        List<T> entries = gson.fromJson(element, type);
        return entries != null ? entries : new ArrayList<>();
    }

}
