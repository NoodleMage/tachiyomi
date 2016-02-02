package eu.kanade.tachiyomi;

import android.app.Application;
import android.os.Build;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricGradleTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import eu.kanade.tachiyomi.data.backup.BackupManager;
import eu.kanade.tachiyomi.data.database.DatabaseHelper;
import eu.kanade.tachiyomi.data.database.models.Category;
import eu.kanade.tachiyomi.data.database.models.Manga;

import static org.assertj.core.api.Assertions.assertThat;

@Config(constants = BuildConfig.class, sdk = Build.VERSION_CODES.LOLLIPOP)
@RunWith(RobolectricGradleTestRunner.class)
public class BackupTest {

    DatabaseHelper db;
    BackupManager backupManager;
    Gson gson;
    JsonObject root;

    @Before
    public void setup() {
        Application app = RuntimeEnvironment.application;
        db = new DatabaseHelper(app);
        backupManager = new BackupManager(db);
        gson = new Gson();
        root = new JsonObject();
    }

    @Test
    public void testRestoreCategory() {
        String catName = "cat";
        root.add("categories", gson.toJsonTree(createCategories(catName)));
        backupManager.restoreCategories(root);

        List<Category> dbCats = db.getCategories().executeAsBlocking();
        assertThat(dbCats).hasSize(1);
        assertThat(dbCats.get(0).name).isEqualTo(catName);
    }

    @Test
    public void testRestoreExistingCategory() {
        String catName = "cat";
        db.insertCategory(createCategory(catName)).executeAsBlocking();

        root.add("categories", gson.toJsonTree(createCategories(catName)));
        backupManager.restoreCategories(root);

        List<Category> dbCats = db.getCategories().executeAsBlocking();
        assertThat(dbCats).hasSize(1);
        assertThat(dbCats.get(0).name).isEqualTo(catName);
    }

    @Test
    public void testRestoreCategories() {
        root.add("categories", gson.toJsonTree(createCategories("cat", "cat2", "cat3")));
        backupManager.restoreCategories(root);

        List<Category> dbCats = db.getCategories().executeAsBlocking();
        assertThat(dbCats).hasSize(3);
    }

    @Test
    public void testRestoreExistingCategories() {
        db.insertCategories(createCategories("cat", "cat2")).executeAsBlocking();

        root.add("categories", gson.toJsonTree(createCategories("cat", "cat2", "cat3")));
        backupManager.restoreCategories(root);

        List<Category> dbCats = db.getCategories().executeAsBlocking();
        assertThat(dbCats).hasSize(3);
    }

    @Test
    public void testRestoreManga() {
        String mangaName = "title";
        List<Manga> mangas = createMangas(mangaName);
        List<JsonElement> elements = new ArrayList<>();
        for (Manga manga : mangas) {
            JsonObject entry = new JsonObject();
            entry.add("manga", gson.toJsonTree(manga));
            elements.add(entry);
        }
        root.add("mangas", gson.toJsonTree(elements));
        backupManager.restoreMangas(root);

        List<Manga> dbMangas = db.getMangas().executeAsBlocking();
        assertThat(dbMangas).hasSize(1);
        assertThat(dbMangas.get(0).title).isEqualTo(mangaName);
    }

    @Test
    public void testRestoreExistingManga() {
        String mangaName = "title";
        Manga manga = createManga(mangaName);

        db.insertManga(manga).executeAsBlocking();

        List<JsonElement> elements = new ArrayList<>();
        JsonObject entry = new JsonObject();
        entry.add("manga", gson.toJsonTree(manga));
        elements.add(entry);

        root.add("mangas", gson.toJsonTree(elements));
        backupManager.restoreMangas(root);

        List<Manga> dbMangas = db.getMangas().executeAsBlocking();
        assertThat(dbMangas).hasSize(1);
    }

    @Test
    public void testRestoreExistingMangaWithUpdatedFields() {
        String mangaName = "title";
        String updatedThumbnailUrl = "updated thumbnail url";
        Manga manga = createManga(mangaName);
        manga.chapter_flags = 1024;
        manga.thumbnail_url = updatedThumbnailUrl;

        db.insertManga(manga).executeAsBlocking();

        List<JsonElement> elements = new ArrayList<>();
        JsonObject entry = new JsonObject();
        // Create new manga
        manga = createManga(mangaName);
        manga.chapter_flags = 512;

        entry.add("manga", gson.toJsonTree(manga));
        elements.add(entry);

        root.add("mangas", gson.toJsonTree(elements));
        backupManager.restoreMangas(root);

        List<Manga> dbMangas = db.getMangas().executeAsBlocking();
        assertThat(dbMangas).hasSize(1);
        assertThat(dbMangas.get(0).thumbnail_url).isEqualTo(updatedThumbnailUrl);
        assertThat(dbMangas.get(0).chapter_flags).isEqualTo(512);
    }

    private Category createCategory(String name) {
        Category c = new Category();
        c.name = name;
        return c;
    }

    private List<Category> createCategories(String... names) {
        List<Category> cats = new ArrayList<>();
        for (String name : names) {
            cats.add(createCategory(name));
        }
        return cats;
    }

    private Manga createManga(String title) {
        Manga m = new Manga();
        m.title = title;
        m.author = "";
        m.artist = "";
        m.thumbnail_url = "";
        m.genre = "a list of genres";
        m.description = "long description";
        m.url = "url to manga";
        m.favorite = true;
        m.source = 1;
        return m;
    }

    private List<Manga> createMangas(String... titles) {
        List<Manga> mangas = new ArrayList<>();
        for (String title : titles) {
            mangas.add(createManga(title));
        }
        return mangas;
    }

}