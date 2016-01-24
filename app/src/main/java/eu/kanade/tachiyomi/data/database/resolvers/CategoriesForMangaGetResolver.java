package eu.kanade.tachiyomi.data.database.resolvers;

import eu.kanade.tachiyomi.data.database.models.Manga;
import eu.kanade.tachiyomi.data.database.tables.CategoryTable;
import eu.kanade.tachiyomi.data.database.tables.MangaCategoryTable;

public class CategoriesForMangaGetResolver {

    private static final String QUERY = String.format(
            "SELECT %1$s.* FROM %1$s JOIN %2$s ON %1$s.%3$s = %2$s.%4$s",
            CategoryTable.TABLE,
            MangaCategoryTable.TABLE,
            CategoryTable.COLUMN_ID,
            MangaCategoryTable.COLUMN_CATEGORY_ID);

    public static String getQuery(Manga manga) {
        return QUERY + String.format(" WHERE %s=%d", MangaCategoryTable.COLUMN_MANGA_ID, manga.id);
    }
}
