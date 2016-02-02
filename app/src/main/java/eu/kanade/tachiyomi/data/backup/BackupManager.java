package eu.kanade.tachiyomi.data.backup;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.pushtorefresh.storio.sqlite.operations.put.PutResult;

import org.json.JSONException;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import eu.kanade.tachiyomi.data.database.DatabaseHelper;
import eu.kanade.tachiyomi.data.database.models.Category;
import eu.kanade.tachiyomi.data.database.models.Chapter;
import eu.kanade.tachiyomi.data.database.models.Manga;
import eu.kanade.tachiyomi.data.database.models.MangaCategory;
import eu.kanade.tachiyomi.data.database.models.MangaSync;
import rx.Observable;
import rx.schedulers.Schedulers;

/**
 * File format:
 *
 * {
 *     "mangas": [{
 *         "manga": {"id": 1, ...},
 *         "chapters": [{"id": 1, ...}],
 *         "manga_sync": [{"id": 1, ...}],
 *         "categories": ["cat1", "cat2"]
 *     }, { ... }],
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
    private static final String MANGA_SYNC = "manga_sync";
    private static final String CATEGORIES = "categories";

    public BackupManager(DatabaseHelper db) {
        this.db = db;
        gson = new Gson();
    }

    public Observable<Serializable> getBackupObservable(File backupFile) {
        return Observable.defer(() -> {
            final JsonObject root = new JsonObject();

            return Observable.concat(
                    getBackupMangaObservable(root),
                    getBackupCategoryObservable(root)
            ).doOnCompleted(() -> saveFile(backupFile, root));

        }).subscribeOn(Schedulers.io());
    }

    private void saveFile(File backupFile, JsonObject json) {
        FileWriter writer;

        try {
            writer = new FileWriter(backupFile);
            gson.toJson(json, writer);
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Observable<Manga> getBackupMangaObservable(JsonObject root) {
        JsonArray entries = new JsonArray();
        root.add(MANGAS, entries);
        return Observable.from(db.getFavoriteMangas().executeAsBlocking())
                .concatMap(manga -> {
                    try {
                        backupManga(manga, entries);
                        return Observable.just(manga);
                    } catch (JSONException e) {
                        return Observable.error(e);
                    }
                });
    }

    private Observable<Category> getBackupCategoryObservable(JsonObject root) {
        JsonArray entries = new JsonArray();
        root.add(CATEGORIES, entries);
        return Observable.from(db.getCategories().executeAsBlocking())
                .concatMap(category -> {
                    try {
                        backupCategory(category, entries);
                        return Observable.just(category);
                    } catch (JSONException e) {
                        return Observable.error(e);
                    }
                });
    }

    public void backupManga(Manga manga, JsonArray entries) throws JSONException {
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

    public void backupCategory(Category category, JsonArray entries) throws JSONException {
        entries.add(gson.toJsonTree(category));
    }

    public Observable getRestoreObservable(File restoreFile) {
        return Observable.defer(() -> {
            JsonReader reader = null;
            try {
                reader = new JsonReader(new FileReader(restoreFile));
                JsonObject root = new JsonParser().parse(reader).getAsJsonObject();
                restoreCategories(root);
                restoreMangas(root);
                return Observable.just(true);
            } catch (Exception e) {
                return Observable.error(e);
            } finally {
                if (reader != null)
                    try { reader.close(); } catch (IOException e) { /* Do nothing */ }
            }
        }).subscribeOn(Schedulers.io());

    }

    public void restoreCategories(JsonObject root) {
        // Get categories from file and from db
        List<Category> dbCategories = db.getCategories().executeAsBlocking();
        List<Category> backupCategories = gson.fromJson(root.get(CATEGORIES),
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

    public void restoreMangas(JsonObject root) {
        JsonArray backupMangas = gson.fromJson(root.get(MANGAS), JsonArray.class);

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
                dbChapter.last_page_read = backupChapter.last_page_read;
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
        // TODO
    }

    private <T> List<T> getArrayOrEmpty(JsonElement element, Type type) {
        List<T> entries = gson.fromJson(element, type);
        return entries != null ? entries : new ArrayList<>();
    }

}
