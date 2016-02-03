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
 * This class provides the necessary methods to create and restore backups for the data of the
 * application. The backup follows a JSON structure, with the following scheme:
 *
 * {
 *     "mangas": [
 *         {
 *             "manga": {"id": 1, ...},
 *             "chapters": [{"id": 1, ...}, {...}],
 *             "sync": [{"id": 1, ...}, {...}],
 *             "categories": ["cat1", "cat2", ...]
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

    /**
     * Backups the data of the application to a file.
     *
     * @param file the file where the backup will be saved.
     * @throws IOException if there's any IO error.
     */
    public void backupToFile(File file) throws IOException {
        final JsonObject root = backupToJson();

        FileWriter writer = null;
        try {
            writer = new FileWriter(file);
            gson.toJson(root, writer);
        } finally {
            if (writer != null)
                writer.close();
        }
    }

    /**
     * Creates a JSON object containing the backup of the app's data.
     *
     * @return the backup as a JSON object.
     */
    public JsonObject backupToJson() {
        final JsonObject root = new JsonObject();

        // Backup library mangas and its dependencies
        final JsonArray mangaEntries = new JsonArray();
        root.add(MANGAS, mangaEntries);
        for (Manga manga : db.getFavoriteMangas().executeAsBlocking()) {
            mangaEntries.add(backupManga(manga));
        }

        // Backup categories
        final JsonArray categoryEntries = new JsonArray();
        root.add(CATEGORIES, categoryEntries);
        for (Category category : db.getCategories().executeAsBlocking()) {
            categoryEntries.add(backupCategory(category));
        }

        return root;
    }

    /**
     * Backups a manga and its related data (chapters, categories this manga is in, sync...).
     *
     * @param manga the manga to backup.
     * @return a JSON object containing all the data of the manga.
     */
    private JsonObject backupManga(Manga manga) {
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

        return entry;
    }

    /**
     * Backups a category.
     *
     * @param category the category to backup.
     * @return a JSON object containing the data of the category.
     */
    private JsonElement backupCategory(Category category) {
        return gson.toJsonTree(category);
    }

    /**
     * Restores a backup from a file.
     *
     * @param file the file containing the backup.
     * @throws IOException if there's any IO error.
     */
    public void restoreFromFile(File file) throws IOException {
        JsonReader reader = null;
        try {
            reader = new JsonReader(new FileReader(file));
            JsonObject root = new JsonParser().parse(reader).getAsJsonObject();
            restoreFromJson(root);
        } finally {
            if (reader != null)
                reader.close();
        }
    }

    /**
     * Restores a backup from a JSON object. Everything executes in a single transaction so that
     * anything is modified if there's an error.
     *
     * @param root the root of the JSON.
     */
    public void restoreFromJson(JsonObject root) {
        try {
            db.lowLevel().beginTransaction();

            // Restore categories
            JsonElement categories = root.get(CATEGORIES);
            if (categories != null)
                restoreCategories(categories.getAsJsonArray());

            // Restore mangas
            JsonElement mangas = root.get(MANGAS);
            if (mangas != null)
                restoreMangas(mangas.getAsJsonArray());

            db.lowLevel().setTransactionSuccessful();
        } finally {
            db.lowLevel().endTransaction();
        }
    }

    /**
     * Restores the categories.
     *
     * @param jsonCategories the categories of the json.
     */
    private void restoreCategories(JsonArray jsonCategories) {
        // Get categories from file and from db
        List<Category> dbCategories = db.getCategories().executeAsBlocking();
        List<Category> backupCategories = getArrayOrEmpty(jsonCategories,
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

    /**
     * Restores all the mangas and its related data.
     *
     * @param jsonMangas the mangas and its related data (chapters, sync, categories) from the json.
     */
    private void restoreMangas(JsonArray jsonMangas) {
        Type chapterToken = new TypeToken<List<Chapter>>(){}.getType();
        Type mangaSyncToken = new TypeToken<List<MangaSync>>(){}.getType();
        Type categoriesNamesToken = new TypeToken<List<String>>(){}.getType();

        for (JsonElement backupManga : jsonMangas) {
            // Map every entry to objects
            JsonObject element = backupManga.getAsJsonObject();
            Manga manga = gson.fromJson(element.get(MANGA), Manga.class);
            List<Chapter> chapters = getArrayOrEmpty(element.get(CHAPTERS), chapterToken);
            List<MangaSync> sync = getArrayOrEmpty(element.get(MANGA_SYNC), mangaSyncToken);
            List<String> categories = getArrayOrEmpty(element.get(CATEGORIES), categoriesNamesToken);

            // Restore everything related to this manga
            restoreManga(manga);
            restoreChaptersForManga(manga, chapters);
            restoreSyncForManga(manga, sync);
            restoreCategoriesForManga(manga, categories);
        }
    }

    /**
     * Restores a manga.
     *
     * @param manga the manga to restore.
     */
    private void restoreManga(Manga manga) {
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
    }

    /**
     * Restores the chapters of a manga.
     *
     * @param manga the manga whose chapters have to be restored.
     * @param chapters the chapters to restore.
     */
    private void restoreChaptersForManga(Manga manga, List<Chapter> chapters) {
        // Fix foreign keys with the current manga id
        for (Chapter chapter : chapters) {
            chapter.manga_id = manga.id;
        }

        List<Chapter> dbChapters = db.getChapters(manga).executeAsBlocking();
        List<Chapter> chaptersToUpdate = new ArrayList<>();
        for (Chapter backupChapter : chapters) {
            // Try to find existing chapter in db
            int pos = dbChapters.indexOf(backupChapter);
            if (pos != -1) {
                // The chapter is already in the db, only update its fields
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

        // Update database
        if (!chaptersToUpdate.isEmpty()) {
            db.insertChapters(chaptersToUpdate).executeAsBlocking();
        }
    }

    /**
     * Restores the categories a manga is in.
     *
     * @param manga the manga whose categories have to be restored.
     * @param categories the categories to restore.
     */
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

        // Update database
        if (!mangaCategoriesToUpdate.isEmpty()) {
            List<Manga> mangaAsList = new ArrayList<>();
            mangaAsList.add(manga);
            db.deleteOldMangasCategories(mangaAsList).executeAsBlocking();
            db.insertMangasCategories(mangaCategoriesToUpdate).executeAsBlocking();
        }
    }

    /**
     * Restores the sync of a manga.
     *
     * @param manga the manga whose sync have to be restored.
     * @param sync the sync to restore.
     */
    private void restoreSyncForManga(Manga manga, List<MangaSync> sync) {
        // Fix foreign keys with the current manga id
        for (MangaSync mangaSync : sync) {
            mangaSync.manga_id = manga.id;
        }

        List<MangaSync> dbSyncs = db.getMangasSync(manga).executeAsBlocking();
        List<MangaSync> syncToUpdate = new ArrayList<>();
        for (MangaSync backupSync : sync) {
            // Try to find existing chapter in db
            int pos = dbSyncs.indexOf(backupSync);
            if (pos != -1) {
                // The sync is already in the db, only update its fields
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

        // Update database
        if (!syncToUpdate.isEmpty()) {
            db.insertMangasSync(syncToUpdate).executeAsBlocking();
        }
    }

    /**
     * Returns a list of items from a json element, or an empty list if the element is null.
     *
     * @param element the json to be mapped to a list of items.
     * @param type the gson mapping to restore the list.
     * @param <T> the type of the returned list
     * @return a list of items.
     */
    private <T> List<T> getArrayOrEmpty(JsonElement element, Type type) {
        List<T> entries = gson.fromJson(element, type);
        return entries != null ? entries : new ArrayList<>();
    }

}
