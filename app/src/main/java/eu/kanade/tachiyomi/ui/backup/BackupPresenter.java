package eu.kanade.tachiyomi.ui.backup;

import android.os.Bundle;

import java.io.File;

import javax.inject.Inject;

import eu.kanade.tachiyomi.data.backup.BackupManager;
import eu.kanade.tachiyomi.data.database.DatabaseHelper;
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter;
import rx.Observable;

public class BackupPresenter extends BasePresenter<BackupFragment> {

    @Inject DatabaseHelper db;

    private BackupManager backupManager;
    private File backupFile;
    private File restoreFile;

    private static final int CREATE_BACKUP = 1;
    private static final int RESTORE_BACKUP = 2;

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        backupManager = new BackupManager(db);

        restartableLatestCache(CREATE_BACKUP,
                this::getBackupObservable,
                (view, next) -> {});

        restartableLatestCache(RESTORE_BACKUP,
                this::getRestoreObservable,
                (view, next) -> {});
    }

    public void createBackup(File backupFile) {
        if (isUnsubscribed(CREATE_BACKUP)) {
            this.backupFile = backupFile;
            start(CREATE_BACKUP);
        }
    }

    public void restoreBackup(File restoreFile) {
        if (isUnsubscribed(RESTORE_BACKUP)) {
            this.restoreFile = restoreFile;
            start(RESTORE_BACKUP);
        }
    }

    private Observable getBackupObservable() {
        return backupManager.getBackupObservable(backupFile);
    }

    private Observable getRestoreObservable() {
        return backupManager.getRestoreObservable(restoreFile);
    }

}
