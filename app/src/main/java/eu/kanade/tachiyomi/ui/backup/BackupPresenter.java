package eu.kanade.tachiyomi.ui.backup;

import android.os.Bundle;

import com.google.gson.Gson;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.Serializable;
import java.util.List;

import javax.inject.Inject;

import eu.kanade.tachiyomi.data.database.DatabaseHelper;
import eu.kanade.tachiyomi.data.database.models.Category;
import eu.kanade.tachiyomi.data.database.models.Chapter;
import eu.kanade.tachiyomi.data.database.models.Manga;
import eu.kanade.tachiyomi.data.database.models.MangaSync;
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter;
import rx.Observable;
import rx.schedulers.Schedulers;

public class BackupPresenter extends BasePresenter<BackupFragment> {

    @Inject DatabaseHelper db;

    private Gson gson;
    private File backupFile;

    private static final int CREATE_BACKUP = 1;

    private static final String MANGA = "manga";
    private static final String MANGAS = "mangas";
    private static final String CHAPTERS = "chapters";
    private static final String MANGA_SYNC = "manga_sync";
    private static final String CATEGORIES = "categories";

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        gson = new Gson();

        restartableLatestCache(CREATE_BACKUP,
                this::getBackupObservable,
                (view, manga) -> {},
                null);
    }

    public void createBackup(File backupFile) {
        if (isUnsubscribed(CREATE_BACKUP)) {
            this.backupFile = backupFile;
            start(CREATE_BACKUP);
        }
    }

    private Observable<Serializable> getBackupObservable() {
        return Observable.defer(() -> {
            final JSONObject root = new JSONObject();

            return Observable.concat(getBackupMangaObservable(root),
                    getBackupCategoryObservable(root))
                    .doOnCompleted(() -> saveFile(root));

        }).subscribeOn(Schedulers.io());
    }

    private Observable<Manga> getBackupMangaObservable(JSONObject root) {
        return db.getLibraryMangas().asRxObservable()
                .subscribeOn(Schedulers.trampoline())
                .take(1)
                .flatMap(Observable::from)
                .concatMap(manga -> {
                    try {
                        backupManga(manga, root);
                        return Observable.just(manga);
                    } catch (JSONException e) {
                        return Observable.error(e);
                    }
                });
    }

    private Observable<Category> getBackupCategoryObservable(JSONObject root) {
        return db.getCategories().asRxObservable()
                .subscribeOn(Schedulers.trampoline())
                .take(1)
                .flatMap(Observable::from)
                .concatMap(category -> {
                    try {
                        backupCategory(category, root);
                        return Observable.just(category);
                    } catch (JSONException e) {
                        return Observable.error(e);
                    }
                });
    }

    private void backupManga(Manga manga, JSONObject root) throws JSONException {
        // Entry for this manga
        JSONObject entry = new JSONObject();

        // Backup manga fields
        entry.put(MANGA, gson.toJson(manga));

        // Backup all the chapters
        List<Chapter> chapters = db.getChapters(manga).executeAsBlocking();
        if (!chapters.isEmpty()) {
            entry.put(CHAPTERS, gson.toJson(chapters));
        }

        // Backup manga sync
        List<MangaSync> mangaSync = db.getMangasSync(manga).executeAsBlocking();
        if (!mangaSync.isEmpty()) {
            entry.put(MANGA_SYNC, gson.toJson(mangaSync));
        }

        // Backup categories for this manga
        List<Category> categoriesForManga = db.getCategoriesForManga(manga).executeAsBlocking();
        if (!categoriesForManga.isEmpty()) {
            for (Category category : categoriesForManga) {
                entry.accumulate(CATEGORIES, category.name);
            }
        }

        // Finally add it to the manga list
        root.accumulate(MANGAS, entry);
    }

    private void backupCategory(Category category, JSONObject root) throws JSONException {
        root.accumulate(CATEGORIES, gson.toJson(category));
    }

    private void saveFile(JSONObject json) {
        FileOutputStream outputStream;

        try {
            outputStream = new FileOutputStream(backupFile);
            outputStream.write(json.toString().getBytes());
            outputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
